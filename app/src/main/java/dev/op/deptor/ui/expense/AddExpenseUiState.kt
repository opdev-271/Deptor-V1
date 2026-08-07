package dev.op.deptor.ui.expense

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Payer
import dev.op.deptor.domain.model.Split
import dev.op.deptor.domain.model.SplitMethod
import dev.op.deptor.domain.money.ExchangeRate
import dev.op.deptor.domain.money.MoneyFormat
import dev.op.deptor.domain.money.PercentFormat
import dev.op.deptor.domain.recurrence.RecurrenceFrequency
import dev.op.deptor.domain.split.BillItem
import dev.op.deptor.domain.split.ExpenseSplitter
import dev.op.deptor.domain.split.splitByItems

/** Rozepsaná položka účtu. Drží text, protože uživatel je běžně uprostřed psaní. */
data class BillItemInput(
    val key: Long,
    val name: String = "",
    val amountText: String = "",
    val consumers: Set<MemberId> = emptySet(),
) {
    fun amount(currency: CurrencyCode): Money? =
        if (amountText.isBlank()) null else MoneyFormat.parse(amountText, currency).getOrNull()
}

/**
 * Stav formuláře výdaje.
 *
 * Vstupy se drží jako text, ne jako čísla: uživatel je běžně rozepsaný („33," nebo
 * prázdné pole) a převádět to na číslo při každém stisku klávesy by znamenalo buď
 * hlásit chybu po každém znaku, nebo si stav domýšlet. Převod na doménové typy proto
 * probíhá až v [splits] a [payers], které vrací [Result] — UI z něj vezme buď náhled,
 * nebo konkrétní důvod, proč to zatím nejde uložit.
 */
data class AddExpenseUiState(
    val settlement: Boolean = false,
    /** Měna skupiny — v ní se počítají salda. */
    val groupCurrency: CurrencyCode = CurrencyCode.CZK,
    /** Měna, ve které je zadaný tenhle výdaj. Může se od měny skupiny lišit. */
    val currency: CurrencyCode = CurrencyCode.CZK,
    val rateText: String = "",
    val rateFromCache: Boolean = false,
    val loadingRate: Boolean = false,
    val members: List<Member> = emptyList(),
    val amountText: String = "",
    val description: String = "",
    /** `null` = výdaj bez kategorie; kategorie je dobrovolná. */
    val category: ExpenseCategory? = null,
    /** `null` = jednorázový výdaj. */
    val repeat: RecurrenceFrequency? = null,
    val splitMethod: SplitMethod = SplitMethod.EQUAL,

    /** Účastníci rovného dělení. U ostatních metod rozhodují vyplněné vstupy. */
    val participantIds: Set<MemberId> = emptySet(),
    val exactInputs: Map<MemberId, String> = emptyMap(),
    val items: List<BillItemInput> = emptyList(),
    val taxText: String = "",
    val tipText: String = "",
    val percentInputs: Map<MemberId, String> = emptyMap(),
    val weightInputs: Map<MemberId, String> = emptyMap(),

    val multiplePayers: Boolean = false,
    val payerId: MemberId? = null,
    val payerInputs: Map<MemberId, String> = emptyMap(),

    val saving: Boolean = false,
    val error: String? = null,
    val loading: Boolean = true,
) {

    /**
     * Částka výdaje.
     *
     * U rozpadu na položky se **nezadává, ale dopočítává** ze součtu položek, daně
     * a spropitného. Nechat uživatele zadat celek zvlášť by znamenalo dvě čísla,
     * která se můžou rozejít, a účtenku stejně opisuje po položkách.
     */
    val amount: Money?
        get() = if (splitMethod == SplitMethod.ITEMS && !settlement) {
            billTotal.takeIf { it.isPositive }
        } else {
            MoneyFormat.parse(amountText, currency).getOrNull()
        }

    val tax: Money get() = parseOrZero(taxText)

    val tip: Money get() = parseOrZero(tipText)

    /** Součet položek plus daň plus spropitné. */
    val billTotal: Money
        get() = Money(items.sumOf { it.amount(currency)?.minor ?: 0L } + tax.minor + tip.minor)

    val amountInvalid: Boolean get() = amountText.isNotBlank() && amount == null

    /** Je výdaj v jiné měně než skupina, a tedy potřebuje kurz? */
    val needsRate: Boolean get() = currency != groupCurrency

    val exchangeRate: ExchangeRate?
        get() = if (needsRate) ExchangeRate.parse(rateText).getOrNull() else null

    val rateInvalid: Boolean get() = needsRate && rateText.isNotBlank() && exchangeRate == null

    /** Kolik výdaj dělá v měně skupiny — to je číslo, které se promítne do sald. */
    val convertedAmount: Money?
        get() {
            val total = amount ?: return null
            if (!needsRate) return total
            return exchangeRate?.convert(total, currency, groupCurrency)
        }

    val rateProblem: String?
        get() = when {
            !needsRate -> null
            loadingRate -> null
            rateText.isBlank() -> "Kurz se nepodařilo načíst, zadej ho ručně"
            exchangeRate == null -> "Kurz musí být kladné číslo"
            else -> null
        }

    /** Kdo a kolik reálně zaplatil. */
    val payers: Result<List<Payer>>
        get() {
            val total = amount ?: return Result.failure(IllegalStateException("Zadej částku"))

            if (!multiplePayers) {
                val payer = payerId ?: return Result.failure(IllegalStateException("Vyber, kdo zaplatil"))
                return Result.success(listOf(Payer(payer, total)))
            }

            val entries = filledAmounts(payerInputs)
                ?: return Result.failure(IllegalStateException("Některá částka plátce není platná"))

            if (entries.isEmpty()) {
                return Result.failure(IllegalStateException("Vyplň, kdo kolik zaplatil"))
            }

            val paid = Money(entries.sumOf { it.second.minor })
            if (paid != total) {
                return Result.failure(
                    IllegalStateException(
                        "Plátci dohromady zadali ${format(paid)}, ale výdaj je ${format(total)}",
                    ),
                )
            }
            return Result.success(entries.map { (member, money) -> Payer(member, money) })
        }

    /** Komu a kolik výdaj připadne. */
    val splits: Result<List<Split>>
        get() {
            val total = amount ?: return Result.failure(IllegalStateException("Zadej částku"))

            if (settlement) {
                val receiver = participantIds.singleOrNull()
                    ?: return Result.failure(IllegalStateException("Vyber, komu se platí"))
                return Result.success(listOf(Split(receiver, total)))
            }

            return when (splitMethod) {
                SplitMethod.EQUAL -> equalSplits(total)
                SplitMethod.EXACT -> exactSplits(total)
                SplitMethod.PERCENT -> percentSplits(total)
                SplitMethod.SHARES -> shareSplits(total)
                SplitMethod.ITEMS -> itemSplits()
            }
        }

    /** Náhled rozdělení; prázdný, dokud zadání nedává smysl. */
    val previewSplits: List<Split> get() = splits.getOrDefault(emptyList())

    /**
     * Důvod, proč to zatím nejde uložit — nebo `null`, když je vše v pořádku.
     *
     * Dokud uživatel nic nezadal, mlčíme: hlásit chybu do prázdného formuláře je
     * otravné. U položek se to nedá poznat podle pole částky, protože to se
     * nevyplňuje — rozhoduje, jestli je aspoň jedna položka rozepsaná.
     */
    val splitProblem: String?
        get() = when {
            splitMethod == SplitMethod.ITEMS && !settlement ->
                if (billUntouched) null else splits.exceptionOrNull()?.message

            amountText.isBlank() -> null
            else -> splits.exceptionOrNull()?.message
        }

    val payerProblem: String?
        get() = if (amount == null) null else payers.exceptionOrNull()?.message

    private val billUntouched: Boolean
        get() = items.isEmpty() || items.all { it.name.isBlank() && it.amountText.isBlank() }

    val canSave: Boolean
        get() = !saving &&
            amount?.isPositive == true &&
            (!needsRate || exchangeRate != null) &&
            payers.isSuccess &&
            splits.isSuccess &&
            (!settlement || participantIds.singleOrNull() != payerId)

    private fun equalSplits(total: Money): Result<List<Split>> {
        val participants = members.map { it.id }.filter { it in participantIds }
        if (participants.isEmpty()) {
            return Result.failure(IllegalStateException("Vyber, mezi koho se výdaj dělí"))
        }
        return runCatching { ExpenseSplitter.equally(total, participants) }
    }

    private fun exactSplits(total: Money): Result<List<Split>> {
        val entries = filledAmounts(exactInputs)
            ?: return Result.failure(IllegalStateException("Některá zadaná částka není platná"))

        if (entries.isEmpty()) {
            return Result.failure(IllegalStateException("Vyplň, kolik připadne na koho"))
        }

        val sum = Money(entries.sumOf { it.second.minor })
        if (sum != total) {
            val difference = Money(total.minor - sum.minor)
            val hint = if (difference.isPositive) {
                "chybí ${format(difference)}"
            } else {
                "přebývá ${format(difference.abs())}"
            }
            return Result.failure(IllegalStateException("Rozděleno ${format(sum)} z ${format(total)} — $hint"))
        }
        return runCatching { ExpenseSplitter.exact(total, entries) }
    }

    private fun percentSplits(total: Money): Result<List<Split>> {
        val entries = filled(percentInputs) { PercentFormat.parseBasisPoints(it).getOrNull() }
            ?: return Result.failure(IllegalStateException("Některé procento není platné"))

        if (entries.isEmpty()) {
            return Result.failure(IllegalStateException("Vyplň procenta"))
        }

        val sum = entries.sumOf { it.second }
        if (sum != PercentFormat.FULL) {
            return Result.failure(
                IllegalStateException(
                    "Procenta dávají ${PercentFormat.format(sum)}, musí dát 100 %",
                ),
            )
        }
        return runCatching { ExpenseSplitter.byPercent(total, entries) }
    }

    private fun itemSplits(): Result<List<Split>> {
        if (items.isEmpty()) {
            return Result.failure(IllegalStateException("Přidej položky účtu"))
        }
        if (items.any { it.name.isBlank() && it.amountText.isBlank() }) {
            return Result.failure(IllegalStateException("Některá položka je prázdná"))
        }
        if (items.any { it.amount(currency) == null }) {
            return Result.failure(IllegalStateException("Některá položka nemá platnou částku"))
        }
        items.firstOrNull { it.consumers.isEmpty() }?.let { item ->
            val label = item.name.ifBlank { "bez názvu" }
            return Result.failure(IllegalStateException("U položky \u201A$label\u2018 není nikdo, kdo si ji dal"))
        }
        if (taxText.isNotBlank() && MoneyFormat.parse(taxText, currency).isFailure) {
            return Result.failure(IllegalStateException("Daň není platná částka"))
        }
        if (tipText.isNotBlank() && MoneyFormat.parse(tipText, currency).isFailure) {
            return Result.failure(IllegalStateException("Spropitné není platná částka"))
        }

        return runCatching {
            splitByItems(
                items = items.map { item ->
                    BillItem(
                        name = item.name,
                        amount = requireNotNull(item.amount(currency)),
                        consumers = members.map { it.id }.filter { it in item.consumers },
                    )
                },
                tax = tax,
                tip = tip,
            )
        }
    }

    private fun parseOrZero(text: String): Money =
        if (text.isBlank()) Money.ZERO else MoneyFormat.parse(text, currency).getOrDefault(Money.ZERO)

    private fun shareSplits(total: Money): Result<List<Split>> {
        val entries = filled(weightInputs) { it.trim().toIntOrNull()?.takeIf { value -> value >= 0 } }
            ?: return Result.failure(IllegalStateException("Podíl musí být celé nezáporné číslo"))

        if (entries.none { it.second > 0 }) {
            return Result.failure(IllegalStateException("Alespoň jeden člen musí mít podíl větší než nula"))
        }
        return runCatching { ExpenseSplitter.byWeights(total, entries) }
    }

    /**
     * Vyplněné vstupy převedené na hodnoty; `null`, když je některý vyplněný vstup
     * neplatný. Prázdná pole se ignorují — nevyplněný člen se prostě neúčastní.
     */
    private fun <T> filled(inputs: Map<MemberId, String>, parse: (String) -> T?): List<Pair<MemberId, T>>? {
        val result = mutableListOf<Pair<MemberId, T>>()
        for (member in members) {
            val text = inputs[member.id].orEmpty()
            if (text.isBlank()) continue
            val value = parse(text) ?: return null
            result += member.id to value
        }
        return result
    }

    private fun filledAmounts(inputs: Map<MemberId, String>): List<Pair<MemberId, Money>>? =
        filled(inputs) { MoneyFormat.parse(it, currency).getOrNull() }

    private fun format(money: Money): String = MoneyFormat.format(money, currency)
}
