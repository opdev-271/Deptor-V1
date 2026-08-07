package dev.op.deptor.ui.expense

import androidx.lifecycle.SavedStateHandle
import dev.op.deptor.MainDispatcherRule
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.recurrence.RecurrenceFrequency
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
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nastavení opakování při zadávání výdaje. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepeatTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()
    private val recurrences = FakeRecurrenceRepository()
    private val exchangeRates = FakeExchangeRateRepository()

    private suspend fun viewModel(): AddExpenseViewModel {
        val groupId = groups.createGroup("Byt", CurrencyCode.CZK, "Anna").getOrThrow()
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
    fun `vydaj se ve vychozim stavu neopakuje`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAmountChange("500")
        viewModel.save {}
        advanceUntilIdle()

        assertNull(viewModel.state.value.repeat)
        assertTrue(recurrences.recurrences.value.isEmpty())
    }

    @Test
    fun `opakovani zalozi pravidlo i prvni vydaj`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAmountChange("15000")
        viewModel.onDescriptionChange("Nájem")
        viewModel.onRepeatChange(RecurrenceFrequency.MONTHLY)
        viewModel.save {}
        advanceUntilIdle()

        // Výdaj vzniká hned, pravidlo se stará o další období.
        assertEquals(1, transactions.transactions.value.size)
        assertEquals(1, recurrences.recurrences.value.size)
    }

    @Test
    fun `pravidlo zacina az dalsim obdobim`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAmountChange("15000")
        viewModel.onRepeatChange(RecurrenceFrequency.MONTHLY)
        viewModel.save {}
        advanceUntilIdle()

        // Kdyby začínalo dneškem, hned by dnešní výdaj vygenerovalo podruhé.
        val rule = recurrences.recurrences.value.single().rule
        assertEquals(LocalDate.now().plusMonths(1), rule.startDate)
        assertTrue(rule.startDate.isAfter(LocalDate.now()))
    }

    @Test
    fun `vzor pravidla odpovida ulozenemu vydaji`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAmountChange("15000")
        viewModel.onDescriptionChange("Nájem")
        viewModel.onRepeatChange(RecurrenceFrequency.MONTHLY)
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        val template = recurrences.recurrences.value.single().template

        assertEquals(Money(1_500_000), template.amount)
        assertEquals("Nájem", template.description)
        assertEquals(saved.splits, template.splits)
        assertEquals(saved.payers, template.payers)
    }

    @Test
    fun `opetovne klepnuti opakovani zrusi`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onRepeatChange(RecurrenceFrequency.WEEKLY)
        assertEquals(RecurrenceFrequency.WEEKLY, viewModel.state.value.repeat)

        viewModel.onRepeatChange(RecurrenceFrequency.WEEKLY)
        assertNull(viewModel.state.value.repeat)
    }

    @Test
    fun `zmena frekvence prepise predchozi`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onRepeatChange(RecurrenceFrequency.WEEKLY)
        viewModel.onRepeatChange(RecurrenceFrequency.YEARLY)

        assertEquals(RecurrenceFrequency.YEARLY, viewModel.state.value.repeat)
    }

    @Test
    fun `pri selhani ulozeni vydaje pravidlo nevznikne`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        transactions.failNextWrite = IllegalStateException("síť je pryč")

        viewModel.onAmountChange("15000")
        viewModel.onRepeatChange(RecurrenceFrequency.MONTHLY)
        viewModel.save {}
        advanceUntilIdle()

        // Pravidlo bez výdaje by generovalo něco, co uživatel nikdy neviděl.
        assertTrue(recurrences.recurrences.value.isEmpty())
    }
}
