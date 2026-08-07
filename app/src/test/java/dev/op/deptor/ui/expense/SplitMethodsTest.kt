package dev.op.deptor.ui.expense

import androidx.lifecycle.SavedStateHandle
import dev.op.deptor.MainDispatcherRule
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.SplitMethod
import dev.op.deptor.domain.money.PercentFormat
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

/** Rozúčtování: přesné částky, procenta, podíly a více plátců. */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitMethodsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()
    private val exchangeRates = FakeExchangeRateRepository()
    private val recurrences = FakeRecurrenceRepository()

    private suspend fun prepare(): Triple<AddExpenseViewModel, List<MemberId>, String> {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()
        groups.addMember(groupId, "Bob").getOrThrow()
        groups.addMember(groupId, "Cyril").getOrThrow()

        val viewModel = AddExpenseViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.GROUP_ID to groupId.value)),
            groups = groups,
            transactions = transactions,
            recurrences = recurrences,
            exchangeRates = exchangeRates,
        )
        return Triple(viewModel, groups.groups.value.single().members.map { it.id }, groupId.value)
    }

    // --- přesné částky ---

    @Test
    fun `presne castky se ulozi tak jak byly zadany`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.EXACT)
        viewModel.onExactChange(members[0], "700")
        viewModel.onExactChange(members[1], "300")
        viewModel.onExactChange(members[2], "")

        assertTrue(viewModel.state.value.canSave)
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(SplitMethod.EXACT, saved.splitMethod)
        assertEquals(2, saved.splits.size)
        assertEquals(Money(70_000), saved.splits.first { it.memberId == members[0] }.amount)
    }

    @Test
    fun `presne castky ktere nesedi na celek nejde ulozit`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.EXACT)
        viewModel.onExactChange(members[0], "700")
        viewModel.onExactChange(members[1], "200")
        // Přepnutí metody pole předvyplní, takže třetího je nutné vynulovat,
        // jinak by se jeho předvyplněná částka do součtu započítala.
        viewModel.onExactChange(members[2], "")

        assertFalse(viewModel.state.value.canSave)
        // Chybová hláška musí říct, kolik chybí — jinak uživatel jen hádá.
        assertTrue(
            viewModel.state.value.splitProblem?.contains("chybí") == true,
            "hláška nepomůže: ${viewModel.state.value.splitProblem}",
        )
    }

    @Test
    fun `prebytek se hlasi jinak nez schodek`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.EXACT)
        viewModel.onExactChange(members[0], "700")
        viewModel.onExactChange(members[1], "500")
        viewModel.onExactChange(members[2], "")

        assertTrue(
            viewModel.state.value.splitProblem?.contains("přebývá") == true,
            "hláška nepomůže: ${viewModel.state.value.splitProblem}",
        )
    }

    @Test
    fun `predvyplnene presne castky uz sedi na celek`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.EXACT)

        // Předvyplnění musí být rovnou uložitelné, ne výchozí bod k opravování chyby.
        assertTrue(
            viewModel.state.value.canSave,
            "předvyplněno nevalidně: ${viewModel.state.value.splitProblem}",
        )
    }

    // --- procenta ---

    @Test
    fun `procenta se prepoctou na castky`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.PERCENT)
        viewModel.onPercentChange(members[0], "50")
        viewModel.onPercentChange(members[1], "30")
        viewModel.onPercentChange(members[2], "20")

        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(SplitMethod.PERCENT, saved.splitMethod)
        assertEquals(Money(50_000), saved.splits.first { it.memberId == members[0] }.amount)
        assertEquals(Money(30_000), saved.splits.first { it.memberId == members[1] }.amount)
        assertEquals(Money(100_000), Money(saved.splits.sumOf { it.amount.minor }))
    }

    @Test
    fun `procenta ktera nedavaji sto nejde ulozit`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.PERCENT)
        viewModel.onPercentChange(members[0], "50")
        viewModel.onPercentChange(members[1], "30")
        viewModel.onPercentChange(members[2], "")

        assertFalse(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.splitProblem?.contains("100 %") == true)
    }

    @Test
    fun `desetinna procenta nic neztrati`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.PERCENT)
        viewModel.onPercentChange(members[0], "33,33")
        viewModel.onPercentChange(members[1], "33,33")
        viewModel.onPercentChange(members[2], "33,34")

        viewModel.save {}
        advanceUntilIdle()

        assertEquals(100_000L, transactions.transactions.value.single().splits.sumOf { it.amount.minor })
    }

    @Test
    fun `predvyplnena procenta davaji presne sto`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.PERCENT)

        // Tři lidé a 100 % nejde beze zbytku — předvyplnění to musí ustát.
        val entries = viewModel.state.value.percentInputs.values
            .map { PercentFormat.parseBasisPoints(it).getOrThrow() }
        assertEquals(PercentFormat.FULL, entries.sum())
        assertTrue(viewModel.state.value.canSave)
    }

    // --- podíly ---

    @Test
    fun `podily rozdeli v pomeru`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("400")
        viewModel.onSplitMethodChange(SplitMethod.SHARES)
        viewModel.onWeightChange(members[0], "2")
        viewModel.onWeightChange(members[1], "1")
        viewModel.onWeightChange(members[2], "1")

        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(SplitMethod.SHARES, saved.splitMethod)
        assertEquals(Money(20_000), saved.splits.first { it.memberId == members[0] }.amount)
        assertEquals(Money(10_000), saved.splits.first { it.memberId == members[1] }.amount)
    }

    @Test
    fun `dite s polovicnim podilem`() = runTest {
        // Případ přímo ze zadání: dítě platí poloviční podíl.
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("500")
        viewModel.onSplitMethodChange(SplitMethod.SHARES)
        viewModel.onWeightChange(members[0], "2")
        viewModel.onWeightChange(members[1], "2")
        viewModel.onWeightChange(members[2], "1")

        val preview = viewModel.state.value.previewSplits
        assertEquals(Money(20_000), preview.first { it.memberId == members[0] }.amount)
        assertEquals(Money(10_000), preview.first { it.memberId == members[2] }.amount)
        assertEquals(50_000L, preview.sumOf { it.amount.minor })
    }

    @Test
    fun `same nulove podily nejde ulozit`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("400")
        viewModel.onSplitMethodChange(SplitMethod.SHARES)
        members.forEach { viewModel.onWeightChange(it, "0") }

        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun `desetinny podil je chyba`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("400")
        viewModel.onSplitMethodChange(SplitMethod.SHARES)
        viewModel.onWeightChange(members[0], "1,5")

        assertFalse(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.splitProblem?.contains("celé") == true)
    }

    // --- více plátců ---

    @Test
    fun `dva platci na jednom vydaji`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("900")
        viewModel.toggleMultiplePayers()
        viewModel.onPayerAmountChange(members[0], "600")
        viewModel.onPayerAmountChange(members[1], "300")

        assertTrue(viewModel.state.value.canSave, viewModel.state.value.payerProblem.orEmpty())
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(2, saved.payers.size)
        assertEquals(Money(60_000), saved.payers.first { it.memberId == members[0] }.amount)
        assertEquals(Money(90_000), Money(saved.payers.sumOf { it.amount.minor }))
    }

    @Test
    fun `platci kteri nesedi na celek nejde ulozit`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("900")
        viewModel.toggleMultiplePayers()
        viewModel.onPayerAmountChange(members[0], "600")
        viewModel.onPayerAmountChange(members[1], "100")

        assertFalse(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.payerProblem != null)
    }

    @Test
    fun `zapnuti vice platcu nabidne celou castku dosavadnimu platci`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("900")
        viewModel.toggleMultiplePayers()

        // Z předvyplněného stavu se upravuje snáz než z prázdných polí — a hlavně
        // je rovnou uložitelný.
        assertEquals(members[0], viewModel.state.value.payerInputs.keys.single())
        assertTrue(viewModel.state.value.canSave)
    }

    @Test
    fun `vice platcu a procenta zaroven`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.toggleMultiplePayers()
        viewModel.onPayerAmountChange(members[0], "400")
        viewModel.onPayerAmountChange(members[1], "600")
        viewModel.onSplitMethodChange(SplitMethod.PERCENT)
        viewModel.onPercentChange(members[0], "25")
        viewModel.onPercentChange(members[1], "25")
        viewModel.onPercentChange(members[2], "50")

        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        assertEquals(100_000L, saved.payers.sumOf { it.amount.minor })
        assertEquals(100_000L, saved.splits.sumOf { it.amount.minor })
    }

    // --- přepínání metod ---

    @Test
    fun `prepnuti metody nezahodi rozepsane vstupy`() = runTest {
        val (viewModel, members) = prepare()
        advanceUntilIdle()

        viewModel.onAmountChange("1000")
        viewModel.onSplitMethodChange(SplitMethod.EXACT)
        viewModel.onExactChange(members[0], "700")

        viewModel.onSplitMethodChange(SplitMethod.PERCENT)
        viewModel.onSplitMethodChange(SplitMethod.EXACT)

        assertEquals("700", viewModel.state.value.exactInputs[members[0]])
    }

    @Test
    fun `rovnym dilem zustava vychozi metodou`() = runTest {
        val (viewModel, _) = prepare()
        advanceUntilIdle()

        assertEquals(SplitMethod.EQUAL, viewModel.state.value.splitMethod)
    }
}
