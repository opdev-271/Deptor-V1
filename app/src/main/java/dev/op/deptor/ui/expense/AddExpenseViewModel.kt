package dev.op.deptor.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.SplitMethod
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.money.MoneyFormat
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceFrequency
import dev.op.deptor.domain.recurrence.RecurrenceId
import dev.op.deptor.domain.recurrence.RecurrenceRule
import dev.op.deptor.domain.repository.ExchangeRateRepository
import dev.op.deptor.domain.repository.RecurrenceRepository
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.domain.repository.RateSource
import dev.op.deptor.domain.repository.TransactionRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groups: GroupRepository,
    private val transactions: TransactionRepository,
    private val recurrences: RecurrenceRepository,
    private val exchangeRates: ExchangeRateRepository,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle.get<String>(Routes.GROUP_ID)))

    /** Stabilní klíč položky — index by se po smazání přeházel a Compose by ztratil stav polí. */
    private var nextItemKey = 0L

    private val _state = MutableStateFlow(AddExpenseUiState())
    val state: StateFlow<AddExpenseUiState> = _state.asStateFlow()

    init {
        val presetFrom = savedStateHandle.get<String>(Routes.SETTLE_FROM)
        val presetTo = savedStateHandle.get<String>(Routes.SETTLE_TO)
        val presetAmount = savedStateHandle.get<String>(Routes.SETTLE_AMOUNT)?.toLongOrNull()
        val isSettlement = presetFrom != null || presetTo != null || presetAmount != null

        viewModelScope.launch {
            val group = groups.observeGroup(groupId).filterNotNull().first()

            _state.update { state ->
                state.copy(
                    settlement = isSettlement,
                    groupCurrency = group.currency,
                    currency = group.currency,
                    members = group.members,
                    loading = false,
                    payerId = presetFrom?.let(::MemberId) ?: group.members.firstOrNull()?.id,
                    participantIds = when {
                        presetTo != null -> setOf(MemberId(presetTo))
                        isSettlement -> emptySet()
                        // U výdaje je výchozí stav „platí se za všechny" — nejčastější případ,
                        // takže běžný výdaj zvládne uživatel na pár tapnutí.
                        else -> group.members.map { it.id }.toSet()
                    },
                    amountText = presetAmount
                        ?.let { MoneyFormat.toEditableText(Money(it), group.currency) }
                        .orEmpty(),
                    description = if (isSettlement) "Vyrovnání" else "",
                )
            }
        }
    }

    fun onAmountChange(value: String) = _state.update { it.copy(amountText = value, error = null) }

    /**
     * Změna měny výdaje.
     *
     * Kurz se hned zkusí načíst, ale zůstává editovatelný — v zahraničí bez signálu
     * je ruční zadání jediná cesta, jak výdaj vůbec uložit.
     */
    fun onCurrencyChange(currency: CurrencyCode) {
        val groupCurrency = _state.value.groupCurrency
        _state.update {
            it.copy(
                currency = currency,
                rateText = if (currency == groupCurrency) "" else it.rateText,
                loadingRate = currency != groupCurrency,
                error = null,
            )
        }
        if (currency == groupCurrency) return

        viewModelScope.launch {
            exchangeRates.rate(currency, groupCurrency).fold(
                onSuccess = { quoted ->
                    _state.update { state ->
                        // Ručně zadaný kurz nepřepisujeme, jen doplňujeme prázdné pole.
                        if (state.currency != currency) {
                            state
                        } else {
                            state.copy(
                                rateText = quoted.rate.value.toPlainString(),
                                rateFromCache = quoted.source == RateSource.CACHED,
                                loadingRate = false,
                            )
                        }
                    }
                },
                onFailure = {
                    _state.update { state ->
                        state.copy(loadingRate = false, rateFromCache = false)
                    }
                },
            )
        }
    }

    fun onRateChange(value: String) = _state.update {
        it.copy(rateText = value, rateFromCache = false, error = null)
    }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value) }

    /** Opětovné klepnutí na zvolené opakování ho zruší. */
    fun onRepeatChange(frequency: RecurrenceFrequency) = _state.update {
        it.copy(repeat = if (it.repeat == frequency) null else frequency)
    }

    /** Opětovné klepnutí na vybranou kategorii ji zruší — výdaj kategorii mít nemusí. */
    fun onCategoryChange(category: ExpenseCategory) = _state.update {
        it.copy(category = if (it.category == category) null else category)
    }

    /**
     * Přepnutí metody rozdělení.
     *
     * Vstupy předchozí metody se **nemažou**: uživatel se často mezi metodami rozmýšlí
     * a přijít o rozepsané částky jen proto, že se podíval na procenta, by bylo otravné.
     */
    fun onSplitMethodChange(method: SplitMethod) = _state.update { state ->
        val prefilled = when {
            // Předvyplnění rovným dílem dá uživateli rozumný výchozí bod k úpravě
            // místo prázdných polí.
            method == SplitMethod.SHARES && state.weightInputs.isEmpty() ->
                state.copy(weightInputs = state.members.associate { it.id to "1" })

            method == SplitMethod.EXACT && state.exactInputs.isEmpty() ->
                state.copy(exactInputs = suggestedExactInputs(state))

            method == SplitMethod.PERCENT && state.percentInputs.isEmpty() ->
                state.copy(percentInputs = suggestedPercentInputs(state))

            // S prázdným účtem se nedá začít, takže rovnou jedna položka.
            method == SplitMethod.ITEMS && state.items.isEmpty() ->
                state.copy(items = listOf(newItem(state)))

            else -> state
        }
        prefilled.copy(splitMethod = method, error = null)
    }

    fun addBillItem() = _state.update { it.copy(items = it.items + newItem(it), error = null) }

    fun removeBillItem(key: Long) = _state.update { state ->
        state.copy(items = state.items.filterNot { it.key == key }, error = null)
    }

    fun onItemNameChange(key: Long, value: String) = updateItem(key) { it.copy(name = value) }

    fun onItemAmountChange(key: Long, value: String) = updateItem(key) { it.copy(amountText = value) }

    fun toggleItemConsumer(key: Long, memberId: MemberId) = updateItem(key) { item ->
        val updated = item.consumers.toMutableSet()
        if (!updated.add(memberId)) updated.remove(memberId)
        item.copy(consumers = updated)
    }

    fun onTaxChange(value: String) = _state.update { it.copy(taxText = value, error = null) }

    fun onTipChange(value: String) = _state.update { it.copy(tipText = value, error = null) }

    private fun updateItem(key: Long, transform: (BillItemInput) -> BillItemInput) =
        _state.update { state ->
            state.copy(
                items = state.items.map { if (it.key == key) transform(it) else it },
                error = null,
            )
        }

    /**
     * Nová položka je předvyplněná všemi členy — u restauračního účtu je sdílená
     * položka běžnější než ta, kterou si dal jeden, a odklikat pár lidí je rychlejší
     * než je všechny naklikat.
     */
    private fun newItem(state: AddExpenseUiState) = BillItemInput(
        key = nextItemKey++,
        consumers = state.members.map { it.id }.toSet(),
    )

    fun onExactChange(memberId: MemberId, value: String) = _state.update {
        it.copy(exactInputs = it.exactInputs + (memberId to value), error = null)
    }

    fun onPercentChange(memberId: MemberId, value: String) = _state.update {
        it.copy(percentInputs = it.percentInputs + (memberId to value), error = null)
    }

    fun onWeightChange(memberId: MemberId, value: String) = _state.update {
        it.copy(weightInputs = it.weightInputs + (memberId to value), error = null)
    }

    fun onPayerChange(id: MemberId) = _state.update { it.copy(payerId = id, error = null) }

    fun onPayerAmountChange(memberId: MemberId, value: String) = _state.update {
        it.copy(payerInputs = it.payerInputs + (memberId to value), error = null)
    }

    fun toggleMultiplePayers() = _state.update { state ->
        val enabling = !state.multiplePayers
        state.copy(
            multiplePayers = enabling,
            // Při zapnutí se celá částka nabídne dosavadnímu plátci — z toho se
            // snáz upravuje, než z prázdných polí.
            payerInputs = if (enabling && state.payerInputs.isEmpty()) {
                state.payerId?.let { payer ->
                    state.amount?.let { mapOf(payer to MoneyFormat.toEditableText(it, state.currency)) }
                }.orEmpty()
            } else {
                state.payerInputs
            },
            error = null,
        )
    }

    fun toggleParticipant(id: MemberId) = _state.update { state ->
        if (state.settlement) {
            // Vyrovnání má právě jednoho příjemce, výběr se tedy nepřepíná, ale nahrazuje.
            state.copy(participantIds = setOf(id))
        } else {
            val updated = state.participantIds.toMutableSet()
            if (!updated.add(id)) updated.remove(id)
            state.copy(participantIds = updated)
        }
    }

    fun selectAllParticipants() = _state.update { state ->
        state.copy(participantIds = state.members.map { it.id }.toSet())
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return

        val amount = current.amount ?: return
        val payers = current.payers.getOrNull() ?: return
        val splits = current.splits.getOrNull() ?: return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }

            val transaction = runCatching {
                Transaction(
                    // Skutečné id přidělí Firestore při zápisu.
                    id = TransactionId(""),
                    groupId = groupId,
                    type = if (current.settlement) TransactionType.SETTLEMENT else TransactionType.EXPENSE,
                    amount = amount,
                    currency = current.currency,
                    date = Instant.now(),
                    description = current.description.trim(),
                    payers = payers,
                    splits = splits,
                    splitMethod = if (current.settlement) SplitMethod.EXACT else current.splitMethod,
                    exchangeRate = current.exchangeRate,
                    categoryId = current.category?.id,
                )
            }

            transaction.fold(
                onSuccess = { built ->
                    transactions.addTransaction(built).fold(
                        onSuccess = {
                            // Opakování se zakládá až po úspěšném uložení prvního výdaje:
                            // pravidlo bez výdaje by generovalo něco, co uživatel nikdy neviděl.
                            current.repeat?.let { frequency -> saveRecurrence(built, frequency) }
                            _state.update { it.copy(saving = false) }
                            onSaved()
                        },
                        onFailure = { error ->
                            _state.update {
                                it.copy(saving = false, error = "Uložení se nepovedlo: ${error.message}")
                            }
                        },
                    )
                },
                onFailure = { error ->
                    // Sem se dostane porušený invariant z konstruktoru Transaction —
                    // radši to zastavit tady než uložit záznam, který rozhodí salda.
                    _state.update {
                        it.copy(saving = false, error = error.message ?: "Neplatný výdaj")
                    }
                },
            )
        }
    }

    /**
     * Založí pravidlo opakování z právě uloženého výdaje.
     *
     * Začátek je **až další období**, ne dnešek — dnešní výdaj už existuje a pravidlo
     * začínající dneškem by ho hned vygenerovalo znovu.
     */
    private suspend fun saveRecurrence(transaction: Transaction, frequency: RecurrenceFrequency) {
        val today = LocalDate.now()
        val start = when (frequency) {
            RecurrenceFrequency.DAILY -> today.plusDays(1)
            RecurrenceFrequency.WEEKLY -> today.plusWeeks(1)
            RecurrenceFrequency.MONTHLY -> today.plusMonths(1)
            RecurrenceFrequency.YEARLY -> today.plusYears(1)
        }

        recurrences.addRecurrence(
            Recurrence(
                id = RecurrenceId(""),
                groupId = groupId,
                rule = RecurrenceRule(frequency = frequency, startDate = start),
                template = transaction,
            ),
        )
    }

    private fun suggestedExactInputs(state: AddExpenseUiState): Map<MemberId, String> {
        val total = state.amount ?: return emptyMap()
        val participants = state.members.map { it.id }.filter { it in state.participantIds }
        if (participants.isEmpty()) return emptyMap()

        return runCatching {
            dev.op.deptor.domain.split.ExpenseSplitter.equally(total, participants)
                .associate { it.memberId to MoneyFormat.toEditableText(it.amount, state.currency) }
        }.getOrDefault(emptyMap())
    }

    private fun suggestedPercentInputs(state: AddExpenseUiState): Map<MemberId, String> {
        val participants = state.members.map { it.id }.filter { it in state.participantIds }
        if (participants.isEmpty()) return emptyMap()

        // Stejná metoda největšího zbytku jako u částek, jen nad procenty — jinak by
        // předvyplněná procenta u tří lidí nedala 100 %.
        val shares = dev.op.deptor.domain.split.distribute(
            Money(dev.op.deptor.domain.money.PercentFormat.FULL.toLong()),
            List(participants.size) { 1L },
        )
        return participants.zip(shares).associate { (member, share) ->
            member to dev.op.deptor.domain.money.PercentFormat.toEditableText(share.minor.toInt())
        }
    }
}
