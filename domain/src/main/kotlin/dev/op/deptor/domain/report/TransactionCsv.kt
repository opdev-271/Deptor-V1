package dev.op.deptor.domain.report

import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.money.MoneyFormat
import dev.op.deptor.domain.money.convertedTo
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Export transakcí do CSV.
 *
 * Oddělovač je **středník**, ne čárka: desetinná čárka je v českém prostředí norma
 * a s čárkou jako oddělovačem by se soubor rozpadl hned na první částce. Středník
 * je zároveň to, co čeká české Excel při dvojkliku.
 *
 * Názvy kategorií se tu nepřekládají — export nese id, aby byl strojově čitelný
 * a nezávislý na jazyce UI.
 */
object TransactionCsv {

    private const val DELIMITER = ';'
    private const val NEWLINE = "\r\n"

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val HEADER = listOf(
        "datum",
        "popis",
        "typ",
        "kategorie",
        "castka",
        "mena",
        "kurz",
        "castka_ve_mene_skupiny",
        "mena_skupiny",
        "platci",
        "podily",
    )

    fun build(group: Group, transactions: List<Transaction>, zone: ZoneId = ZoneId.systemDefault()): String {
        val names = group.members.associate { it.id to it.name }

        val rows = transactions.map { transaction ->
            val converted = runCatching { transaction.convertedTo(group.currency) }.getOrNull()

            listOf(
                transaction.date.atZone(zone).format(dateFormatter),
                transaction.description,
                transaction.type.name,
                ExpenseCategory.fromId(transaction.categoryId)?.id?.value.orEmpty(),
                decimal(transaction.amount, transaction.currency.code),
                transaction.currency.code,
                transaction.exchangeRate?.value?.toPlainString().orEmpty(),
                converted?.let { decimal(it.amount, group.currency.code) }.orEmpty(),
                group.currency.code,
                entries(transaction.payers.map { it.memberId to it.amount }, names, transaction.currency.code),
                entries(transaction.splits.map { it.memberId to it.amount }, names, transaction.currency.code),
            )
        }

        return (listOf(HEADER) + rows).joinToString(NEWLINE) { row ->
            row.joinToString(DELIMITER.toString()) { escape(it) }
        } + NEWLINE
    }

    /** Jméno a částka pro každého plátce či podíl, oddělené svislítkem. */
    private fun entries(
        entries: List<Pair<MemberId, Money>>,
        names: Map<MemberId, String>,
        currencyCode: String,
    ): String = entries.joinToString(" | ") { (member, amount) ->
        "${names[member] ?: member.value}: ${decimal(amount, currencyCode)}"
    }

    private fun decimal(amount: Money, currencyCode: String): String =
        MoneyFormat.toEditableText(amount, dev.op.deptor.domain.model.CurrencyCode(currencyCode))
            .replace('.', ',')

    /**
     * Escapování hodnoty do CSV.
     *
     * Kromě běžného obalení uvozovkami řeší i **vzorcovou injekci**: hodnota začínající
     * na `=`, `+`, `-` nebo `@` se v Excelu i v Google Sheets vyhodnotí jako vzorec.
     * Popis výdaje si přitom uživatel píše sám a může ho vyplnit kdokoli ze skupiny,
     * takže by přes export šlo spustit cizímu člověku vzorec v tabulce. Před takovou
     * hodnotu se proto vkládá apostrof.
     */
    private fun escape(value: String): String {
        val guarded = if (value.isNotEmpty() && value.first() in FORMULA_TRIGGERS) {
            "'$value"
        } else {
            value
        }

        val needsQuotes = guarded.any { it == DELIMITER || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${guarded.replace("\"", "\"\"")}\"" else guarded
    }

    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /** Název souboru bez znaků, které by na některém systému nešly uložit. */
    fun fileName(group: Group): String {
        val safeName = group.name
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "skupina" }
        return "deptor-$safeName.csv"
    }
}
