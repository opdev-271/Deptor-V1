package dev.op.deptor.ui.expense

import androidx.lifecycle.SavedStateHandle
import dev.op.deptor.MainDispatcherRule
import dev.op.deptor.domain.balance.computeBalances
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.money.ExchangeRate
import dev.op.deptor.domain.repository.RateSource
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
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Výdaj zadaný v jiné měně, než v jaké skupina počítá. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForeignCurrencyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groups = FakeGroupRepository()
    private val transactions = FakeTransactionRepository()
    private val recurrences = FakeRecurrenceRepository()

    private suspend fun viewModel(
        rates: FakeExchangeRateRepository = FakeExchangeRateRepository(),
    ): AddExpenseViewModel {
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()
        groups.addMember(groupId, "Bob").getOrThrow()

        return AddExpenseViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.GROUP_ID to groupId.value)),
            groups = groups,
            transactions = transactions,
            recurrences = recurrences,
            exchangeRates = rates,
        )
    }

    @Test
    fun `vychozi mena je mena skupiny a kurz neni potreba`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(CurrencyCode.CZK, state.currency)
        assertFalse(state.needsRate)
        assertNull(state.exchangeRate)
    }

    @Test
    fun `zmena meny nacte kurz`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.needsRate)
        assertEquals(ExchangeRate(BigDecimal("25")), state.exchangeRate)
        assertFalse(state.loadingRate)
    }

    @Test
    fun `prepocet ukazuje kolik to bude v mene skupiny`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onAmountChange("100")

        // 100 € při kurzu 25 = 2500 Kč
        assertEquals(Money(250_000), viewModel.state.value.convertedAmount)
    }

    @Test
    fun `ulozeny vydaj si nese menu i kurz`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onAmountChange("100")
        viewModel.save {}
        advanceUntilIdle()

        val saved = transactions.transactions.value.single()
        // Uloží se původní částka v eurech, ne přepočet — účtenka pak sedí.
        assertEquals(Money(10_000), saved.amount)
        assertEquals(CurrencyCode.EUR, saved.currency)
        assertEquals(ExchangeRate(BigDecimal("25")), saved.exchangeRate)
    }

    @Test
    fun `cizi vydaj se do sald zapocita prepoctenou castkou`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onAmountChange("100")
        viewModel.save {}
        advanceUntilIdle()

        val members = groups.groups.value.single().members.map { it.id }
        val balances = computeBalances(transactions.transactions.value, members, CurrencyCode.CZK)

        // Anna zaplatila 2500 Kč a spotřebovala polovinu → +1250 Kč.
        assertEquals(Money(125_000), balances.first { it.memberId == members[0] }.amount)
        assertEquals(0L, balances.sumOf { it.amount.minor })
    }

    @Test
    fun `navrat k mene skupiny kurz zahodi`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onCurrencyChange(CurrencyCode.CZK)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.needsRate)
        assertNull(state.exchangeRate)
        assertEquals("", state.rateText)
    }

    @Test
    fun `bez kurzu nejde cizi vydaj ulozit`() = runTest {
        val viewModel = viewModel(FakeExchangeRateRepository(failing = true))
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onAmountChange("100")

        // Uložit ho s kurzem 1 by tiše rozbilo salda — radši nejde uložit vůbec.
        assertFalse(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.rateProblem != null)
    }

    @Test
    fun `rucne zadany kurz vydaj odblokuje`() = runTest {
        val viewModel = viewModel(FakeExchangeRateRepository(failing = true))
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onAmountChange("100")
        // Bez signálu v zahraničí je ruční zadání jediná cesta, jak výdaj uložit.
        viewModel.onRateChange("24,5")

        assertTrue(viewModel.state.value.canSave)
        assertEquals(Money(245_000), viewModel.state.value.convertedAmount)
    }

    @Test
    fun `nesmyslny kurz se odmitne`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onAmountChange("100")
        viewModel.onRateChange("abc")

        assertFalse(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.rateInvalid)
    }

    @Test
    fun `kurz z cache se pozna`() = runTest {
        val viewModel = viewModel(
            FakeExchangeRateRepository(
                rate = ExchangeRate(BigDecimal("24")),
                source = RateSource.CACHED,
            ),
        )
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()

        // Uživatel musí poznat, že kurz nemusí být aktuální, aby ho mohl opravit.
        assertTrue(viewModel.state.value.rateFromCache)
    }

    @Test
    fun `rucni uprava zrusi priznak cache`() = runTest {
        val viewModel = viewModel(
            FakeExchangeRateRepository(source = RateSource.CACHED),
        )
        advanceUntilIdle()

        viewModel.onCurrencyChange(CurrencyCode.EUR)
        advanceUntilIdle()
        viewModel.onRateChange("26")

        assertFalse(viewModel.state.value.rateFromCache)
    }
}
