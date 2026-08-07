package dev.op.deptor.ui.expense

import androidx.lifecycle.SavedStateHandle
import dev.op.deptor.MainDispatcherRule
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.SplitMethod
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

/** Rozpad účtu na položky. */
@OptIn(ExperimentalCoroutinesApi::class)
class BillItemsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()
    private val exchangeRates = FakeExchangeRateRepository()
    private val recurrences = FakeRecurrenceRepository()

    private suspend fun prepare(): Pair<AddExpenseViewModel, List<MemberId>> {
        val groupId = groups.createGroup("Restaurace", CurrencyCode.CZK, "Anna").getOrThrow()
        groups.addMember(groupId, "Bob").getOrThrow()

        val viewModel = AddExpenseViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.GROUP_ID to groupId.value)),
            groups = groups,
            transactions = transactions,
            recurrences = recurrences,
            exchangeRates = exchangeRates,
        )
        return viewModel to groups.groups.value.single().members.map { it.id }
    }

    @Test
    fun `prepnuti na polozky rovnou zalozi prvni polozku`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)

        // S prázdným účtem se nedá začít.
        assertEquals(1, viewModel.state.value.items.size)
    }

    @Test
    fun `nova polozka je predvyplnena vsemi cleny`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)

        // Sdílená položka je na účtu běžnější než ta pro jednoho.
        assertEquals(members.toSet(), viewModel.state.value.items.single().consumers)
    }

    @Test
    fun `castka se dopocita ze soucty polozek`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        val first = viewModel.state.value.items.single().key
        viewModel.onItemAmountChange(first, "180")
        viewModel.addBillItem()
        val second = viewModel.state.value.items.last().key
        viewModel.onItemAmountChange(second, "50")

        assertEquals(Money(23_000), viewModel.state.value.amount)
    }

    @Test
    fun `dan a spropitne se pricitaji k celku`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        viewModel.onItemAmountChange(viewModel.state.value.items.single().key, "200")
        viewModel.onTaxChange("20")
        viewModel.onTipChange("30")

        assertEquals(Money(25_000), viewModel.state.value.amount)
    }

    @Test
    fun `kazdy plati jen svou polozku`() = runTest {
        val (viewModel, members) = prepare()
        val (anna, bob) = members
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        val first = viewModel.state.value.items.single().key
        viewModel.onItemNameChange(first, "steak")
        viewModel.onItemAmountChange(first, "300")
        viewModel.toggleItemConsumer(first, bob) // zůstane jen Anna

        viewModel.addBillItem()
        val second = viewModel.state.value.items.last().key
        viewModel.onItemNameChange(second, "polévka")
        viewModel.onItemAmountChange(second, "100")
        viewModel.toggleItemConsumer(second, anna) // zůstane jen Bob

        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(SplitMethod.ITEMS, saved.splitMethod)
        assertEquals(Money(30_000), saved.splits.first { it.memberId == anna }.amount)
        assertEquals(Money(10_000), saved.splits.first { it.memberId == bob }.amount)
        assertEquals(Money(40_000), saved.amount)
    }

    @Test
    fun `spropitne se rozpocita podle utraty`() = runTest {
        val (viewModel, members) = prepare()
        val (anna, bob) = members
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        val first = viewModel.state.value.items.single().key
        viewModel.onItemAmountChange(first, "300")
        viewModel.toggleItemConsumer(first, bob)

        viewModel.addBillItem()
        val second = viewModel.state.value.items.last().key
        viewModel.onItemAmountChange(second, "100")
        viewModel.toggleItemConsumer(second, anna)

        viewModel.onTipChange("40")
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        // Kdo si dal dráž, platí i větší díl spropitného.
        assertEquals(Money(33_000), saved.splits.first { it.memberId == anna }.amount)
        assertEquals(Money(11_000), saved.splits.first { it.memberId == bob }.amount)
    }

    @Test
    fun `polozka bez konzumenta ulozit nejde`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        val key = viewModel.state.value.items.single().key
        viewModel.onItemAmountChange(key, "200")
        members.forEach { viewModel.toggleItemConsumer(key, it) }

        assertFalse(viewModel.state.value.canSave)
        assertTrue(
            viewModel.state.value.splitProblem?.contains("nikdo") == true,
            "hláška nepomůže: ${viewModel.state.value.splitProblem}",
        )
    }

    @Test
    fun `neplatna cena polozky ulozit nejde`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        viewModel.onItemAmountChange(viewModel.state.value.items.single().key, "nesmysl")

        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun `prazdny ucet ulozit nejde`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        viewModel.removeBillItem(viewModel.state.value.items.single().key)

        assertFalse(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    @Test
    fun `odebrani polozky nechá ostatní beze zmeny`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        val first = viewModel.state.value.items.single().key
        viewModel.onItemNameChange(first, "první")
        viewModel.addBillItem()
        val second = viewModel.state.value.items.last().key
        viewModel.onItemNameChange(second, "druhá")

        viewModel.removeBillItem(first)

        // Klíče jsou stabilní, takže se po smazání nepřehází obsah polí.
        assertEquals("druhá", viewModel.state.value.items.single().name)
    }

    @Test
    fun `soucet podilu sedi na celkovou castku`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onSplitMethodChange(SplitMethod.ITEMS)
        val key = viewModel.state.value.items.single().key
        // Nic z toho nevychází beze zbytku.
        viewModel.onItemAmountChange(key, "333,33")
        viewModel.onTipChange("11,11")

        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(saved.amount.minor, saved.splits.sumOf { it.amount.minor })
        assertEquals(saved.amount.minor, saved.payers.sumOf { it.amount.minor })
        assertEquals(2, members.size)
    }
}
