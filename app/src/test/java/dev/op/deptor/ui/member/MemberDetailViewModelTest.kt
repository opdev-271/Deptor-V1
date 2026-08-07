package dev.op.deptor.ui.member

import androidx.lifecycle.SavedStateHandle
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
import dev.op.deptor.domain.split.ExpenseSplitter
import dev.op.deptor.fakes.FakeGroupRepository
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
class MemberDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()

    // GroupId je value class, na které `lateinit` nejde.
    private lateinit var rawGroupId: String
    private val groupId: GroupId get() = GroupId(rawGroupId)

    private lateinit var members: List<MemberId>

    private suspend fun prepare() {
        rawGroupId = groups.createGroup("Výlet", CurrencyCode.CZK, "Anna").getOrThrow().value
        groups.addMember(groupId, "Bob").getOrThrow()
        groups.addMember(groupId, "Cyril").getOrThrow()
        members = groups.groups.value.single().members.map { it.id }
    }

    private fun viewModel(memberId: MemberId) = MemberDetailViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(Routes.GROUP_ID to groupId.value, Routes.MEMBER_ID to memberId.value),
        ),
        groups = groups,
        transactions = transactions,
    )

    /** Bez odběru se `stateIn(WhileSubscribed)` vůbec nespustí a testy by byly prázdné. */
    private fun MemberDetailViewModel.subscribe(scope: CoroutineScope) {
        scope.launch { state.collect { } }
    }

    private fun expense(payer: MemberId, between: List<MemberId>, amount: Long) = Transaction(
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
    fun `dluznik vidi komu a kolik dluzi`() = runTest {
        prepare()
        val (anna, bob) = members
        transactions.addTransaction(expense(anna, members, 90_000))

        val viewModel = viewModel(bob)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("Bob", state.memberName)
        assertEquals(Money(-30_000), state.balance?.amount)
        assertEquals(1, state.owes.size)
        assertEquals("Anna", state.owes.single().counterpartName)
        assertEquals(Money(30_000), state.owes.single().amount)
        assertTrue(state.owedToMember.isEmpty())
    }

    @Test
    fun `veritel vidi kdo dluzi jemu`() = runTest {
        prepare()
        val anna = members[0]
        transactions.addTransaction(expense(anna, members, 90_000))

        val viewModel = viewModel(anna)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(Money(60_000), state.balance?.amount)
        assertEquals(2, state.owedToMember.size)
        assertTrue(state.owes.isEmpty())
        assertEquals(60_000L, state.owedToMember.sumOf { it.amount.minor })
    }

    @Test
    fun `soucet dluhu odpovida saldu i pri obou smerech`() = runTest {
        prepare()
        val (anna, bob, cyril) = members
        transactions.addTransaction(expense(anna, listOf(anna, bob), 60_000))
        transactions.addTransaction(expense(bob, listOf(bob, cyril), 40_000))

        val viewModel = viewModel(bob)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val state = viewModel.state.value
        val net = state.owedToMember.sumOf { it.amount.minor } - state.owes.sumOf { it.amount.minor }

        // Kdyby se rozešly, uživatel by v detailu viděl jiné číslo než v bilanci.
        assertEquals(state.balance?.amount?.minor, net)
    }

    @Test
    fun `vyrovnany clen nema zadne dluhy`() = runTest {
        prepare()
        val (anna, bob) = members
        transactions.addTransaction(expense(anna, listOf(anna, bob), 60_000))
        transactions.addTransaction(
            Transaction(
                id = TransactionId(""),
                groupId = groupId,
                type = TransactionType.SETTLEMENT,
                amount = Money(30_000),
                currency = CurrencyCode.CZK,
                date = Instant.EPOCH.plusSeconds(1),
                description = "vyrovnání",
                payers = listOf(Payer(bob, Money(30_000))),
                splits = listOf(Split(anna, Money(30_000))),
            ),
        )

        val viewModel = viewModel(bob)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.debts.isEmpty(), "vyrovnaný člen nemá co dlužit: ${state.debts}")
        assertTrue(state.balance?.isSettled == true)
    }

    @Test
    fun `historie obsahuje jen transakce daneho clena`() = runTest {
        prepare()
        val (anna, bob, cyril) = members
        transactions.addTransaction(expense(anna, listOf(anna, bob), 60_000))
        transactions.addTransaction(expense(cyril, listOf(cyril), 10_000))

        val viewModel = viewModel(bob)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.transactions.size)
    }

    @Test
    fun `odebrany clen s dluhem se porad da otevrit`() = runTest {
        prepare()
        val (anna, _, cyril) = members
        transactions.addTransaction(expense(anna, members, 90_000))
        groups.removeMember(groupId, cyril)

        val viewModel = viewModel(cyril)
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        val state = viewModel.state.value
        // Jinak by nebylo kde dohledat, co odebraný člen dluží.
        assertEquals("Bývalý člen", state.memberName)
        assertEquals(Money(-30_000), state.balance?.amount)
        assertEquals(1, state.owes.size)
    }

    @Test
    fun `smazana skupina skonci chybou`() = runTest {
        prepare()
        val viewModel = viewModel(members[0])
        viewModel.subscribe(backgroundScope)
        advanceUntilIdle()

        groups.deleteGroup(groupId)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.error != null)
    }
}
