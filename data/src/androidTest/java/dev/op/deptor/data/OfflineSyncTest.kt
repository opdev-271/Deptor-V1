package dev.op.deptor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.op.deptor.data.repository.FirebaseAuthRepository
import dev.op.deptor.data.repository.FirestoreGroupRepository
import dev.op.deptor.data.repository.FirestoreTransactionRepository
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Payer
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.domain.repository.TransactionRepository
import dev.op.deptor.domain.split.ExpenseSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Offline režim a synchronizace ze zadání (bod 8).
 *
 * Testy běží na `runBlocking`, ne na `runTest`. `runTest` pracuje s virtuálním časem
 * a `withTimeout` v něm vyprší okamžitě, aniž by reálná synchronizace dostala šanci —
 * u integračního testu se skutečným I/O je virtuální čas jen past.
 *
 * Místo letového režimu se používá `disableNetwork()` / `enableNetwork()` přímo na
 * Firestore. Chová se to stejně — SDK obsluhuje zápisy z lokální cache a po obnovení
 * spojení je dožene — ale je to deterministické a opakovatelné, kdežto přepínání
 * letového režimu přes adb je pomalé a časování se pokaždé liší.
 *
 * Stav serveru se čte přes REST mimo Firestore SDK. Kdyby se četl přes SDK, vracel by
 * lokální cache a test by „prošel" i v případě, že se na server nikdy nic nedostalo.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSyncTest {

    private lateinit var auth: AuthRepository
    private lateinit var groups: GroupRepository
    private lateinit var transactions: TransactionRepository

    // GroupId je value class a `lateinit` na ní nejde, drží se proto syrová hodnota.
    private lateinit var rawGroupId: String
    private val groupId: GroupId get() = GroupId(rawGroupId)

    private lateinit var members: List<MemberId>

    @Before
    fun setUp() = runBlocking<Unit> {
        EmulatorAdmin.clearFirestore()
        EmulatorAdmin.clearAuthUsers()

        auth = FirebaseAuthRepository(EmulatorFirebase.auth())
        groups = FirestoreGroupRepository(EmulatorFirebase.firestore(), auth)
        transactions = FirestoreTransactionRepository(EmulatorFirebase.firestore())

        // Síť mohl nechat vypnutou předchozí test.
        runCatching { EmulatorFirebase.firestore().enableNetwork().await() }

        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        rawGroupId = groups.createGroup("Výlet", CurrencyCode.CZK, "Anna").getOrThrow().value
        members = listOf(
            groups.addMember(groupId, "Anna").getOrThrow(),
            groups.addMember(groupId, "Bob").getOrThrow(),
        )
        // Počkat, než se skupina i členové usadí, ať offline část netestuje jejich zápis.
        groups.observeGroup(groupId).first { it != null && it.members.size == 3 }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        // Síť je nutné vrátit, jinak by další test běžel na zamčeném SDK.
        runCatching { EmulatorFirebase.firestore().enableNetwork().await() }
        Unit
    }

    @Test
    fun vydaj_zadany_bez_site_se_hned_zobrazi_a_po_pripojeni_dorazi_na_server() =
        runBlocking {
            val firestore = EmulatorFirebase.firestore()
            firestore.disableNetwork().await()

            // Bez sítě musí zápis projít do lokální cache a UI ho hned uvidí —
            // jinak by aplikace v tunelu nebo v zahraničí byla k ničemu.
            transactions.addTransaction(expense(900)).getOrThrow()

            val offline = withTimeout(30.seconds) {
                transactions.observeTransactions(groupId).first { it.transactions.isNotEmpty() }
            }
            assertEquals(Money(900), offline.transactions.single().amount)
            assertEquals(0, serverTransactions().size, "bez sítě se nesmělo nic odeslat")

            firestore.enableNetwork().await()

            assertEquals(
                1,
                awaitServerTransactions(expected = 1).size,
                "po obnovení spojení měl výdaj dorazit na server",
            )
        }

    @Test
    fun vice_zaznamu_zadanych_offline_dorazi_vsechny() = runBlocking<Unit> {
        val firestore = EmulatorFirebase.firestore()
        firestore.disableNetwork().await()

        repeat(3) { index ->
            transactions.addTransaction(expense(100L * (index + 1))).getOrThrow()
        }

        val offline = withTimeout(30.seconds) {
            transactions.observeTransactions(groupId).first { it.transactions.size == 3 }
        }
        assertEquals(3, offline.transactions.size)
        assertEquals(0, serverTransactions().size)

        firestore.enableNetwork().await()

        assertEquals(3, awaitServerTransactions(expected = 3).size)
    }

    @Test
    fun rozdeleni_spoctene_offline_dorazi_na_server_beze_zmeny() = runBlocking<Unit> {
        val firestore = EmulatorFirebase.firestore()
        firestore.disableNetwork().await()

        // 901 mezi dva nejde beze zbytku — zajímá nás, že se výsledek synchronizací nezmění.
        transactions.addTransaction(expense(901)).getOrThrow()

        val offline = withTimeout(30.seconds) {
            transactions.observeTransactions(groupId).first { it.transactions.isNotEmpty() }
        }
        val offlineSplits = offline.transactions.single().splits
            .associate { it.memberId.value to it.amount.minor }

        firestore.enableNetwork().await()

        val onServer = awaitServerTransactions(expected = 1).single()
        assertEquals(offlineSplits, splitsOf(onServer))
        assertEquals(901L, splitsOf(onServer).values.sum())
    }

    @Test
    fun soubezna_editace_konci_last_write_wins() = runBlocking<Unit> {
        // Firestore konflikty neřeší mergem — vyhrává poslední zápis. Pro MVP je to
        // přijatelné, protože každá transakce je vlastní dokument a kolidovat může
        // jen souběžná editace téhož výdaje. Test to fixuje jako vědomé chování,
        // ne jako náhodu.
        val id = transactions.addTransaction(expense(900)).getOrThrow()
        val original = withTimeout(30.seconds) {
            transactions.observeTransactions(groupId).first { it.transactions.isNotEmpty() }
        }.transactions.single()

        transactions.updateTransaction(original.copy(description = "první")).getOrThrow()
        transactions.updateTransaction(original.copy(description = "druhý")).getOrThrow()

        val result = withTimeout(30.seconds) {
            transactions.observeTransactions(groupId)
                .first { it.transactions.singleOrNull()?.description == "druhý" }
        }

        assertEquals("druhý", result.transactions.single().description)
        assertEquals(id, result.transactions.single().id)
        assertTrue(result.rejected.isEmpty())
    }

    /**
     * Čeká, až na serveru bude [expected] transakcí.
     *
     * HTTP volání blokuje vlákno, proto [Dispatchers.IO].
     */
    private suspend fun awaitServerTransactions(
        expected: Int,
        timeoutMillis: Long = 60_000,
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var documents = serverTransactions()
        while (documents.size < expected && System.currentTimeMillis() < deadline) {
            delay(500)
            documents = serverTransactions()
        }
        documents
    }

    /** Dokumenty přímo ze serveru, mimo lokální cache SDK. */
    private fun serverTransactions(): List<JSONObject> {
        val url = "http://10.0.2.2:8080/v1/projects/${EmulatorFirebase.PROJECT_ID}" +
            "/databases/(default)/documents/groups/${groupId.value}/transactions"
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            // Emulátor obchází security rules, když se přihlásí jako owner.
            setRequestProperty("Authorization", "Bearer owner")
        }
        return try {
            val body = connection.inputStream.bufferedReader().readText()
            val documents = JSONObject(body).optJSONArray("documents") ?: return emptyList()
            (0 until documents.length()).map { documents.getJSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun splitsOf(document: JSONObject): Map<String, Long> {
        val values = document.getJSONObject("fields")
            .getJSONObject("splits")
            .getJSONObject("arrayValue")
            .getJSONArray("values")

        return (0 until values.length()).associate { index ->
            val fields = values.getJSONObject(index).getJSONObject("mapValue").getJSONObject("fields")
            fields.getJSONObject("memberId").getString("stringValue") to
                fields.getJSONObject("amountMinor").getString("integerValue").toLong()
        }
    }

    private fun expense(amount: Long) = Transaction(
        // Prázdné id znamená „přiděl nové" — vyplněné by se použilo jako klíč
        // dokumentu a transakce by si navzájem přepisovaly.
        id = TransactionId(""),
        groupId = groupId,
        type = TransactionType.EXPENSE,
        amount = Money(amount),
        currency = CurrencyCode.CZK,
        date = Instant.now(),
        description = "výdaj",
        payers = listOf(Payer(members.first(), Money(amount))),
        splits = ExpenseSplitter.equally(Money(amount), members),
    )
}
