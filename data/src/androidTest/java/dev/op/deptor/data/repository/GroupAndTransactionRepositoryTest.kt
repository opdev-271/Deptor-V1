package dev.op.deptor.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.op.deptor.data.EmulatorAdmin
import dev.op.deptor.data.EmulatorFirebase
import dev.op.deptor.domain.balance.computeBalances
import dev.op.deptor.domain.balance.simplify
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class GroupAndTransactionRepositoryTest {

    private lateinit var auth: AuthRepository
    private lateinit var groups: GroupRepository
    private lateinit var transactions: TransactionRepository

    @Before
    fun setUp() = runTest {
        EmulatorAdmin.clearFirestore()
        EmulatorAdmin.clearAuthUsers()

        auth = FirebaseAuthRepository(EmulatorFirebase.auth())
        groups = FirestoreGroupRepository(EmulatorFirebase.firestore(), auth)
        transactions = FirestoreTransactionRepository(EmulatorFirebase.firestore())

        auth.signOut()
        auth.signInAnonymously().getOrThrow()
    }

    @Test
    fun zalozeni_skupiny_rovnou_pridá_zakladatele_jako_clena() = runTest(timeout = 60.seconds) {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        val group = groups.observeGroup(groupId).filterNotNull().first { it.members.isNotEmpty() }

        assertEquals("Chata", group.name)
        assertEquals(CurrencyCode.CZK, group.currency)
        assertEquals("Anna", group.members.single().name)
        assertEquals(auth.currentUserOrNull()?.id, group.members.single().userId)
    }

    @Test
    fun virtualni_clen_vznikne_bez_uctu() = runTest(timeout = 60.seconds) {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()
        groups.addMember(groupId, "Bob").getOrThrow()

        val group = groups.observeGroup(groupId).filterNotNull().first { it.members.size == 2 }
        val bob = group.members.first { it.name == "Bob" }

        assertTrue(bob.isVirtual, "Bob nemá účet, má být virtuální člen")
    }

    @Test
    fun vydaj_se_ulozi_a_prijde_zpet_pres_listener() = runTest(timeout = 60.seconds) {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()
        val anna = groups.addMember(groupId, "Anna2").getOrThrow()
        val bob = groups.addMember(groupId, "Bob").getOrThrow()

        transactions.observeTransactions(groupId).test {
            assertEquals(0, awaitItem().transactions.size)

            transactions.addTransaction(expense(groupId, anna, listOf(anna, bob), 900)).getOrThrow()

            val load = awaitItem()
            assertEquals(1, load.transactions.size)
            assertEquals(Money(900), load.transactions.single().amount)
            assertTrue(!load.hasRejected)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cely_scenar_MVP_od_vydaje_po_vyrovnani() = runTest(timeout = 90.seconds) {
        val groupId = groups.createGroup("Výlet", CurrencyCode.CZK, "Anna").getOrThrow()
        val anna = groups.addMember(groupId, "Anna").getOrThrow()
        val bob = groups.addMember(groupId, "Bob").getOrThrow()
        val cyril = groups.addMember(groupId, "Cyril").getOrThrow()
        val everyone = listOf(anna, bob, cyril)

        // Anna zaplatí 900 za všechny → dluží jí Bob i Cyril po 300.
        transactions.addTransaction(expense(groupId, anna, everyone, 900)).getOrThrow()

        val afterExpense = transactions.observeTransactions(groupId)
            .first { it.transactions.size == 1 }

        val balances = computeBalances(afterExpense.transactions, everyone, CurrencyCode.CZK)
        assertEquals(Money(600), balances.first { it.memberId == anna }.amount)

        val transfers = simplify(balances)
        assertEquals(2, transfers.size)
        assertTrue(transfers.all { it.to == anna })

        // Bob svůj díl vyrovná.
        val bobsTransfer = transfers.first { it.from == bob }
        transactions.addTransaction(settlement(groupId, bobsTransfer.from, bobsTransfer.to, bobsTransfer.amount))
            .getOrThrow()

        val afterSettlement = transactions.observeTransactions(groupId)
            .first { it.transactions.size == 2 }

        val finalBalances = computeBalances(afterSettlement.transactions, everyone, CurrencyCode.CZK)
        assertEquals(Money.ZERO, finalBalances.first { it.memberId == bob }.amount)
        assertEquals(Money(300), finalBalances.first { it.memberId == anna }.amount)
        assertEquals(0L, finalBalances.sumOf { it.amount.minor })
    }

    @Test
    fun rozbity_dokument_se_vrati_jako_odmitnuty_a_nezmizi_potichu() = runTest(timeout = 60.seconds) {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        // Zápis mimo doménu — přesně to, co security rules zachytit neumí:
        // součet podílů neodpovídá částce.
        EmulatorFirebase.firestore()
            .collection("groups").document(groupId.value)
            .collection("transactions").document("rozbita")
            .set(
                mapOf(
                    "type" to "EXPENSE",
                    "amountMinor" to 900L,
                    "currency" to "CZK",
                    "date" to com.google.firebase.Timestamp.now(),
                    "description" to "rozbitá",
                    "payers" to listOf(mapOf("memberId" to "anna", "amountMinor" to 900L)),
                    "splits" to listOf(mapOf("memberId" to "anna", "amountMinor" to 500L)),
                ),
            ).await()

        val load = transactions.observeTransactions(groupId).first { it.hasRejected }

        assertEquals(0, load.transactions.size)
        assertEquals("rozbita", load.rejected.single().id)
        assertTrue(
            load.rejected.single().reason.isNotBlank(),
            "odmítnutí musí nést důvod, jinak se to nedá diagnostikovat",
        )
    }

    @Test
    fun smazani_transakce_se_projevi_v_listeneru() = runTest(timeout = 60.seconds) {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()
        val anna = groups.addMember(groupId, "Anna").getOrThrow()

        val id = transactions.addTransaction(expense(groupId, anna, listOf(anna), 500)).getOrThrow()
        transactions.observeTransactions(groupId).first { it.transactions.size == 1 }

        transactions.deleteTransaction(groupId, id).getOrThrow()
        val load = transactions.observeTransactions(groupId).first { it.transactions.isEmpty() }

        assertTrue(load.transactions.isEmpty())
    }

    @Test
    fun skupiny_uzivatele_obsahuji_jen_jeho_skupiny() = runTest(timeout = 60.seconds) {
        groups.createGroup("Moje", CurrencyCode.CZK, "Anna").getOrThrow()

        val mine = groups.observeGroups().first { it.isNotEmpty() }
        assertEquals(listOf("Moje"), mine.map { it.name })

        // Jiný uživatel nesmí tuhle skupinu vidět.
        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        val theirs = groups.observeGroups().first()
        assertTrue(theirs.isEmpty(), "cizí skupina se nesmí objevit: ${theirs.map { it.name }}")
    }

    @Test
    fun anonymni_ucet_lze_povysit_se_zachovanim_uid() = runTest(timeout = 60.seconds) {
        val before = assertNotNull(auth.currentUserOrNull())
        assertTrue(before.isAnonymous)

        groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        val after = auth.linkWithEmail("anna@example.com", "tajneheslo123").getOrThrow()

        assertEquals(before.id, after.id, "povýšením se nesmí změnit UID, jinak zmizí data")
        assertTrue(!after.isAnonymous)

        // A data zůstala dostupná.
        val stillMine = groups.observeGroups().first { it.isNotEmpty() }
        assertEquals(listOf("Chata"), stillMine.map { it.name })
    }

    private fun expense(
        groupId: GroupId,
        payer: MemberId,
        between: List<MemberId>,
        amount: Long,
    ) = Transaction(
        // Prázdné id znamená „přiděl nové" — vyplněné by se použilo jako klíč
        // dokumentu a transakce by si navzájem přepisovaly.
        id = TransactionId(""),
        groupId = groupId,
        type = TransactionType.EXPENSE,
        amount = Money(amount),
        currency = CurrencyCode.CZK,
        date = Instant.now(),
        description = "výdaj",
        payers = listOf(Payer(payer, Money(amount))),
        splits = ExpenseSplitter.equally(Money(amount), between),
    )

    private fun settlement(groupId: GroupId, from: MemberId, to: MemberId, amount: Money) =
        Transaction(
            // Prázdné id znamená „přiděl nové" — vyplněné by se použilo jako klíč
        // dokumentu a transakce by si navzájem přepisovaly.
        id = TransactionId(""),
            groupId = groupId,
            type = TransactionType.SETTLEMENT,
            amount = amount,
            currency = CurrencyCode.CZK,
            date = Instant.now(),
            description = "vyrovnání",
            payers = listOf(Payer(from, amount)),
            splits = listOf(dev.op.deptor.domain.model.Split(to, amount)),
        )
}
