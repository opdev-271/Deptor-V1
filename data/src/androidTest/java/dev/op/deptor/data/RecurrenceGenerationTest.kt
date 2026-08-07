package dev.op.deptor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.op.deptor.data.repository.FirebaseAuthRepository
import dev.op.deptor.data.repository.FirestoreGroupRepository
import dev.op.deptor.data.repository.FirestoreRecurrenceRepository
import dev.op.deptor.data.repository.FirestoreTransactionRepository
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Payer
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceFrequency
import dev.op.deptor.domain.recurrence.RecurrenceId
import dev.op.deptor.domain.recurrence.RecurrenceRule
import dev.op.deptor.domain.recurrence.generateDueTransactions
import dev.op.deptor.domain.split.ExpenseSplitter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Generování opakujících se transakcí proti skutečnému Firestore.
 *
 * Hlavní věc, kterou tenhle test hlídá, je **idempotence**: transakce se zakládají
 * na zařízeních členů, takže stejné pravidlo může vygenerovat víc lidí najednou.
 * Kdyby id nebylo deterministické, nájem by ve skupině byl dvakrát a salda by
 * ukázala dvojnásobek.
 */
@RunWith(AndroidJUnit4::class)
class RecurrenceGenerationTest {

    private lateinit var auth: FirebaseAuthRepository
    private lateinit var groups: FirestoreGroupRepository
    private lateinit var transactions: FirestoreTransactionRepository
    private lateinit var recurrences: FirestoreRecurrenceRepository

    private lateinit var rawGroupId: String
    private val groupId: GroupId get() = GroupId(rawGroupId)
    private lateinit var members: List<MemberId>

    private val zone: ZoneId = ZoneId.of("Europe/Prague")

    @Before
    fun setUp() = runBlocking<Unit> {
        EmulatorAdmin.clearFirestore()
        EmulatorAdmin.clearAuthUsers()
        runCatching { EmulatorFirebase.firestore().enableNetwork().await() }

        auth = FirebaseAuthRepository(EmulatorFirebase.auth())
        groups = FirestoreGroupRepository(EmulatorFirebase.firestore(), auth)
        transactions = FirestoreTransactionRepository(EmulatorFirebase.firestore())
        recurrences = FirestoreRecurrenceRepository(EmulatorFirebase.firestore())

        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        rawGroupId = groups.createGroup("Byt", CurrencyCode.CZK, "Anna").getOrThrow().value
        members = listOf(
            groups.addMember(groupId, "Anna").getOrThrow(),
            groups.addMember(groupId, "Bob").getOrThrow(),
        )
        groups.observeGroup(groupId).first { it != null && it.members.size == 3 }
    }

    private suspend fun addMonthlyRent(start: String): RecurrenceId {
        val amount = Money(1_500_000)
        val template = Transaction(
            id = TransactionId(""),
            groupId = groupId,
            type = TransactionType.EXPENSE,
            amount = amount,
            currency = CurrencyCode.CZK,
            date = Instant.EPOCH,
            description = "Nájem",
            payers = listOf(Payer(members.first(), amount)),
            splits = ExpenseSplitter.equally(amount, members),
        )

        val id = recurrences.addRecurrence(
            Recurrence(
                id = RecurrenceId("nepoužito"),
                groupId = groupId,
                rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, LocalDate.parse(start)),
                template = template,
            ),
        ).getOrThrow()

        withTimeout(30.seconds) { recurrences.observeRecurrences(groupId).first { it.isNotEmpty() } }
        return id
    }

    /** Počet transakcí přímo na serveru, mimo lokální cache SDK. */
    private fun serverTransactionCount(): Int {
        val url = "http://10.0.2.2:8080/v1/projects/${EmulatorFirebase.PROJECT_ID}" +
            "/databases/(default)/documents/groups/$rawGroupId/transactions"
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer owner")
        }
        return try {
            val body = connection.inputStream.bufferedReader().readText()
            val documents = org.json.JSONObject(body).optJSONArray("documents") ?: return 0
            documents.length()
        } finally {
            connection.disconnect()
        }
    }

    /** Počká, až se zápisy usadí, a vrátí výsledný počet. */
    private suspend fun awaitServerTransactionCount(expected: Int): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 30_000
            var count = serverTransactionCount()
            while (count < expected && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(500)
                count = serverTransactionCount()
            }
            // Ještě chvíli počkat, jestli nedorazí něco navíc — duplicita by se jinak
            // schovala za to, že jsme přestali číst hned po dosažení očekávaného počtu.
            kotlinx.coroutines.delay(2_000)
            serverTransactionCount()
        }

    @Test
    fun opakovani_vygeneruje_transakci_za_kazdy_mesic() = runBlocking<Unit> {
        addMonthlyRent("2026-01-01")

        val created = generateDueTransactions(
            groupId = groupId,
            recurrences = recurrences,
            transactions = transactions,
            today = LocalDate.parse("2026-03-15"),
            zone = zone,
        )

        assertEquals(3, created)

        val load = withTimeout(30.seconds) {
            transactions.observeTransactions(groupId).first { it.transactions.size == 3 }
        }
        assertTrue(load.transactions.all { it.description == "Nájem" })
        assertTrue(load.transactions.all { Recurrence.isGenerated(it.id) })
    }

    @Test
    fun dvoji_generovani_nevytvori_duplicitu() = runBlocking<Unit> {
        addMonthlyRent("2026-01-01")

        // Dvě zařízení, stejné pravidlo, stejný den — a mezitím se nestihl uložit
        // posun `generatedThrough`. Proti duplicitě chrání jedině deterministické id.
        repeat(2) {
            generateDueTransactions(
                groupId = groupId,
                recurrences = recurrences,
                transactions = transactions,
                today = LocalDate.parse("2026-03-15"),
                zone = zone,
            )
        }

        // Počítá se přímo na serveru, ne přes listener: ten emituje průběžně a první
        // emise nemusí obsahovat všechny zápisy, takže by test měřil závod, ne duplicity.
        val onServer = awaitServerTransactionCount(expected = 3)
        assertEquals(3, onServer, "nájem vznikl vícekrát")
    }

    @Test
    fun dalsi_beh_uz_nic_nevytvori() = runBlocking<Unit> {
        addMonthlyRent("2026-01-01")

        generateDueTransactions(
            groupId, recurrences, transactions, LocalDate.parse("2026-03-15"), zone,
        )
        // Počkat, než se posun `generatedThrough` propíše.
        withTimeout(30.seconds) {
            recurrences.observeRecurrences(groupId).first { it.first().generatedThrough != null }
        }

        val second = generateDueTransactions(
            groupId, recurrences, transactions, LocalDate.parse("2026-03-15"), zone,
        )
        assertEquals(0, second, "druhý běh neměl co vytvořit")
    }

    @Test
    fun zameskane_mesice_se_dozenou() = runBlocking<Unit> {
        addMonthlyRent("2026-01-01")

        generateDueTransactions(
            groupId, recurrences, transactions, LocalDate.parse("2026-01-15"), zone,
        )
        withTimeout(30.seconds) {
            recurrences.observeRecurrences(groupId).first { it.first().generatedThrough != null }
        }

        // Aplikace se dva měsíce neotevřela.
        val later = generateDueTransactions(
            groupId, recurrences, transactions, LocalDate.parse("2026-03-15"), zone,
        )
        assertEquals(2, later)
    }

    @Test
    fun zruseni_pravidla_nesmaze_uz_vzniklé_vydaje() = runBlocking<Unit> {
        val id = addMonthlyRent("2026-01-01")

        generateDueTransactions(
            groupId, recurrences, transactions, LocalDate.parse("2026-03-15"), zone,
        )
        withTimeout(30.seconds) {
            transactions.observeTransactions(groupId).first { it.transactions.size == 3 }
        }

        recurrences.deleteRecurrence(groupId, id).getOrThrow()

        // Vygenerované výdaje jsou skutečné platby — smazat je se zrušením pravidla
        // by rozhodilo salda. Čte se ze serveru, aby se neměřila jen jedna emise.
        assertEquals(3, awaitServerTransactionCount(expected = 3))
    }

    @Test
    fun vygenerovane_transakce_maji_sedici_soucty() = runBlocking<Unit> {
        addMonthlyRent("2026-01-01")
        generateDueTransactions(
            groupId, recurrences, transactions, LocalDate.parse("2026-02-15"), zone,
        )

        val load = withTimeout(30.seconds) {
            transactions.observeTransactions(groupId).first { it.transactions.size == 2 }
        }

        // Kdyby vzor porušil invariant, mapper by transakce odmítl a skončily by
        // mezi rejected místo v seznamu.
        assertTrue(load.rejected.isEmpty())
        load.transactions.forEach { transaction ->
            assertEquals(transaction.amount.minor, transaction.splits.sumOf { it.amount.minor })
        }
    }
}
