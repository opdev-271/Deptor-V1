package dev.op.deptor.ui.expense

import androidx.lifecycle.SavedStateHandle
import dev.op.deptor.MainDispatcherRule
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.Money
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()
    private val exchangeRates = FakeExchangeRateRepository()
    private val recurrences = FakeRecurrenceRepository()

    private suspend fun viewModel(): AddExpenseViewModel {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()
        groups.addMember(groupId, "Bob").getOrThrow()

        return AddExpenseViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.GROUP_ID to groupId.value)),
            groups = groups,
            transactions = transactions,
            recurrences = recurrences,
            exchangeRates = exchangeRates,
        )
    }

    @Test
    fun `vydaj kategorii mit nemusi`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value.category)

        viewModel.onAmountChange("500")
        assertTrue(viewModel.state.value.canSave, "kategorie je nepovinná")

        viewModel.save {}
        advanceUntilIdle()

        assertNull(transactions.transactions.value.single().categoryId)
    }

    @Test
    fun `vybrana kategorie se ulozi jako stabilni id`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAmountChange("500")
        viewModel.onCategoryChange(ExpenseCategory.FOOD)
        viewModel.save {}
        advanceUntilIdle()

        // Do databáze jde id, ne pořadí v enumu.
        assertEquals(ExpenseCategory.FOOD.id, transactions.transactions.value.single().categoryId)
    }

    @Test
    fun `opetovne klepnuti kategorii zrusi`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCategoryChange(ExpenseCategory.TRANSPORT)
        assertEquals(ExpenseCategory.TRANSPORT, viewModel.state.value.category)

        viewModel.onCategoryChange(ExpenseCategory.TRANSPORT)
        assertNull(viewModel.state.value.category, "druhé klepnutí má výběr zrušit")
    }

    @Test
    fun `zmena kategorie prepise predchozi`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCategoryChange(ExpenseCategory.FOOD)
        viewModel.onCategoryChange(ExpenseCategory.SHOPPING)

        assertEquals(ExpenseCategory.SHOPPING, viewModel.state.value.category)
    }

    @Test
    fun `kategorie se nastavi i u vydaje v cizi mene`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onAmountChange("100")
        viewModel.onCategoryChange(ExpenseCategory.ACCOMMODATION)
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(ExpenseCategory.ACCOMMODATION.id, saved.categoryId)
        assertEquals(Money(10_000), saved.amount)
        assertEquals(CurrencyCode.EUR, saved.currency)
    }
}
