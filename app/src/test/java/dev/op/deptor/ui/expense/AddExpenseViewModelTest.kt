package dev.op.deptor.ui.expense

import androidx.lifecycle.SavedStateHandle
import dev.op.deptor.MainDispatcherRule
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.fakes.FakeExchangeRateRepository
import dev.op.deptor.fakes.FakeGroupRepository
import dev.op.deptor.fakes.FakeRecurrenceRepository
import dev.op.deptor.fakes.FakeTransactionRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()
    private val exchangeRates = FakeExchangeRateRepository()
    private val recurrences = FakeRecurrenceRepository()

    private suspend fun prepareGroup(): String {
        val id = groups.createGroup("Chata", CurrencyCode.CZK, "Já").getOrThrow()
        groups.addMember(id, "Bob").getOrThrow()
        groups.addMember(id, "Cyril").getOrThrow()
        return id.value
    }

    private fun viewModel(vararg extras: Pair<String, String?>, groupId: String) =
        AddExpenseViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.GROUP_ID to groupId) + extras.toMap()),
            groups = groups,
            transactions = transactions,
            recurrences = recurrences,
            exchangeRates = exchangeRates,
        )

    @Test
    fun `vychozi stav je vydaj za vsechny`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.settlement)
        assertEquals(3, state.members.size)
        // Nejčastější případ má být na nula tapnutí — platí ten, kdo výdaj zadává,
        // a dělí se mezi všechny.
        assertEquals(3, state.participantIds.size)
        assertEquals(state.members.first().id, state.payerId)
    }

    @Test
    fun `nahled rozdeleni odpovida tomu co se ulozi`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        viewModel.onAmountChange("900")
        val preview = viewModel.state.value.previewSplits

        viewModel.save {}
        advanceUntilIdle()

        assertEquals(preview, transactions.transactions.value.single().splits)
    }

    @Test
    fun `ulozeny vydaj ma spravny typ a castku`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        viewModel.onAmountChange("900")
        viewModel.onDescriptionChange("Nákup")
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(TransactionType.EXPENSE, saved.type)
        assertEquals(Money(90_000), saved.amount)
        assertEquals("Nákup", saved.description)
        assertEquals(Money(90_000), saved.payers.single().amount)
    }

    @Test
    fun `bez castky nejde ulozit`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.canSave)

        viewModel.onAmountChange("nesmysl")
        assertFalse(viewModel.state.value.canSave)

        viewModel.onAmountChange("0")
        assertFalse(viewModel.state.value.canSave, "nulový výdaj nedává smysl")

        viewModel.onAmountChange("100")
        assertTrue(viewModel.state.value.canSave)
    }

    @Test
    fun `bez ucastniku nejde ulozit`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        viewModel.onAmountChange("900")
        viewModel.state.value.members.forEach { viewModel.toggleParticipant(it.id) }

        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun `odebrani a vraceni ucastnika`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        val bob = viewModel.state.value.members[1].id
        viewModel.toggleParticipant(bob)
        assertFalse(bob in viewModel.state.value.participantIds)

        viewModel.toggleParticipant(bob)
        assertTrue(bob in viewModel.state.value.participantIds)
    }

    @Test
    fun `vydaj jen pro cast skupiny se rozdeli jen mezi ne`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        val cyril = viewModel.state.value.members[2].id
        viewModel.toggleParticipant(cyril)
        viewModel.onAmountChange("500")
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(2, saved.splits.size)
        assertFalse(saved.splits.any { it.memberId == cyril })
        assertEquals(Money(50_000), saved.splits.sumOf { it.amount.minor }.let(::Money))
    }

    @Test
    fun `vyrovnani je predvyplnene z navrhu`() = runTest {
        val groupId = prepareGroup()
        val members = groups.groups.value.single().members
        val bob = members[1].id
        val ja = members[0].id

        val viewModel = viewModel(
            Routes.SETTLE_FROM to bob.value,
            Routes.SETTLE_TO to ja.value,
            Routes.SETTLE_AMOUNT to "30000",
            groupId = groupId,
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.settlement)
        assertEquals(bob, state.payerId)
        assertEquals(setOf(ja), state.participantIds)
        assertEquals(Money(30_000), state.amount)
        assertTrue(state.canSave)
    }

    @Test
    fun `ulozene vyrovnani ma typ SETTLEMENT`() = runTest {
        val groupId = prepareGroup()
        val members = groups.groups.value.single().members

        val viewModel = viewModel(
            Routes.SETTLE_FROM to members[1].id.value,
            Routes.SETTLE_TO to members[0].id.value,
            Routes.SETTLE_AMOUNT to "30000",
            groupId = groupId,
        )
        advanceUntilIdle()

        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(TransactionType.SETTLEMENT, saved.type)
        assertEquals(members[1].id, saved.payers.single().memberId)
        assertEquals(members[0].id, saved.splits.single().memberId)
    }

    @Test
    fun `vyrovnani sam sobe nejde ulozit`() = runTest {
        val groupId = prepareGroup()
        val members = groups.groups.value.single().members
        val ja = members[0].id

        val viewModel = viewModel(
            Routes.SETTLE_FROM to ja.value,
            Routes.SETTLE_TO to ja.value,
            Routes.SETTLE_AMOUNT to "30000",
            groupId = groupId,
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun `selhani zapisu se ukaze uzivateli a nic se neulozi`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        transactions.failNextWrite = IllegalStateException("síť je pryč")

        viewModel.onAmountChange("900")
        var savedCalled = false
        viewModel.save { savedCalled = true }
        advanceUntilIdle()

        assertFalse(savedCalled, "při chybě se nesmí zavírat obrazovka")
        assertTrue(transactions.transactions.value.isEmpty())
        assertTrue(viewModel.state.value.error?.contains("síť je pryč") == true)
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun `zbyle halere se v nahledu rozdeli beze ztraty`() = runTest {
        val groupId = prepareGroup()
        val viewModel = viewModel(groupId = groupId)
        advanceUntilIdle()

        viewModel.onAmountChange("100")
        val preview = viewModel.state.value.previewSplits

        assertEquals(3, preview.size)
        assertEquals(10_000L, preview.sumOf { it.amount.minor })
    }
}
