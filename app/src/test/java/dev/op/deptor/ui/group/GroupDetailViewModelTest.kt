package dev.op.deptor.ui.group

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.op.deptor.MainDispatcherRule
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Payer
import dev.op.deptor.domain.model.Split
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.repository.RejectedDocument
import dev.op.deptor.domain.split.ExpenseSplitter
import dev.op.deptor.fakes.FakeGroupRepository
import dev.op.deptor.fakes.FakeRecurrenceRepository
import dev.op.deptor.fakes.FakeTransactionRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()
    private val recurrences = FakeRecurrenceRepository()

    private suspend fun prepareGroup(): GroupId {
        val id = groups.createGroup("Výlet", CurrencyCode.CZK, "Já").getOrThrow()
        groups.addMember(id, "Bob").getOrThrow()
        groups.addMember(id, "Cyril").getOrThrow()
        return id
    }

    private fun viewModel(groupId: GroupId) = GroupDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Routes.GROUP_ID to groupId.value)),
        groups = groups,
        transactions = transactions,
        recurrences = recurrences,
    )

    /**
     * Naváže odběr stavu.
     *
     * Bez něj je test bezcenný: `state` je `stateIn(WhileSubscribed)`, takže dokud
     * nikdo neodebírá, upstream se vůbec nespustí a `state.value` zůstane na výchozí
     * prázdné hodnotě — tvrzení nad prázdnými seznamy pak procházejí, aniž by cokoli
     * ověřila. V aplikaci odběr obstarává `collectAsStateWithLifecycle`.
     */
    private fun GroupDetailViewModel.subscribe(scope: CoroutineScope) {
        scope.launch { state.collect { } }
    }

    private fun expense(groupId: GroupId, payer: MemberId, between: List<MemberId>, amount: Long) =
        Transaction(
            // Prázdné id = „přiděl nové". Pevné id by se použilo jako klíč
        // a transakce by si navzájem přepisovaly.
        id = TransactionId(""),
            groupId = groupId,
            type = TransactionType.EXPENSE,
            amount = Money(amount),
            currency = CurrencyCode.CZK,
            date = Instant.EPOCH,
            description = "výdaj",
            payers = listOf(Payer(payer, Money(amount))),
            splits = ExpenseSplitter.equally(Money(amount), between),
        )

    @Test
    fun `cely scenar MVP od vydaje po vyrovnani`() = runTest {
        val groupId = prepareGroup()
        val members = groups.groups.value.single().members
        val (ja, bob, cyril) = Triple(members[0].id, members[1].id, members[2].id)

        val viewModel = viewModel(groupId)

        viewModel.state.test {
            awaitItem() // výchozí prázdný stav

            transactions.addTransaction(expense(groupId, ja, listOf(ja, bob, cyril), 90_000))
            advanceUntilIdle()

            val afterExpense = expectMostRecentItem()
            assertEquals(Money(60_000), afterExpense.balances.first { it.member.id == ja }.balance.amount)
            assertEquals(2, afterExpense.suggestedTransfers.size)
            assertTrue(afterExpense.suggestedTransfers.all { it.transfer.to == ja })
            assertEquals("Já", afterExpense.suggestedTransfers.first().toName)

            // Bob pošle svůj díl.
            val bobsTransfer = afterExpense.suggestedTransfers.first { it.transfer.from == bob }
            transactions.addTransaction(
                Transaction(
                    id = TransactionId(""),
                    groupId = groupId,
                    type = TransactionType.SETTLEMENT,
                    amount = bobsTransfer.transfer.amount,
                    currency = CurrencyCode.CZK,
                    date = Instant.EPOCH.plusSeconds(1),
                    description = "vyrovnání",
                    payers = listOf(Payer(bob, bobsTransfer.transfer.amount)),
                    splits = listOf(Split(ja, bobsTransfer.transfer.amount)),
                ),
            )
            advanceUntilIdle()

            val afterSettlement = expectMostRecentItem()
            assertTrue(afterSettlement.balances.first { it.member.id == bob }.balance.isSettled)
            assertEquals(Money(30_000), afterSettlement.balances.first { it.member.id == ja }.balance.amount)
            assertEquals(1, afterSettlement.suggestedTransfers.size)
            assertEquals(0L, afterSettlement.balances.sumOf { it.balance.amount.minor })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `salda jsou serazena od nejvetsiho veritele`() = runTest {
        val groupId = prepareGroup()
        val members = groups.groups.value.single().members
        transactions.addTransaction(
            expense(groupId, members[1].id, members.map { it.id }, 90_000),
        )

        val viewModel = viewModel(groupId)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val amounts = viewModel.state.value.balances.map { it.balance.amount.minor }
        assertEquals(amounts.sortedDescending(), amounts)
    }

    @Test
    fun `vyrovnana skupina nema co navrhovat`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.suggestedTransfers.isEmpty())
        assertTrue(state.balances.all { it.balance.isSettled })
    }

    @Test
    fun `odmitnuty dokument se propise do stavu`() = runTest {
        val groupId = prepareGroup()
        transactions.rejected.value = listOf(RejectedDocument("rozbita", "součet nesedí"))

        val viewModel = viewModel(groupId)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        // Uživatel se to musí dozvědět — jinak by viděl salda spočítaná z neúplných dat.
        assertEquals(1, viewModel.state.value.rejected.size)
        assertEquals("rozbita", viewModel.state.value.rejected.single().id)
    }

    @Test
    fun `byvaly clen s nevyrovnanym saldem z bilance nezmizi`() = runTest {
        val groupId = prepareGroup()
        val members = groups.groups.value.single().members
        val cyril = members[2].id

        transactions.addTransaction(expense(groupId, members[0].id, members.map { it.id }, 90_000))
        groups.removeMember(groupId, cyril)

        val viewModel = viewModel(groupId)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val entry = viewModel.state.value.balances.firstOrNull { it.member.id == cyril }
        assertTrue(entry != null, "člen s dluhem nesmí z bilance zmizet jen proto, že byl odebrán")
        assertEquals(Money(-30_000), entry.balance.amount)
        assertEquals(0L, viewModel.state.value.balances.sumOf { it.balance.amount.minor })
    }

    @Test
    fun `smazana skupina skonci chybou a ne prazdnou obrazovkou`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        groups.deleteGroup(groupId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.error != null, "smazaná skupina se musí projevit jako chyba")
        assertTrue(!state.loading)
    }

    @Test
    fun `mazani transakce prepocita salda`() = runTest {
        val groupId = prepareGroup()
        val members = groups.groups.value.single().members
        transactions.addTransaction(expense(groupId, members[0].id, members.map { it.id }, 90_000))

        val viewModel = viewModel(groupId)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        viewModel.deleteTransaction(viewModel.state.value.transactions.single())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.balances.all { it.balance.isSettled })
    }
}
