package dev.op.deptor.domain.money

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Převod mezi [Money] (nejmenší jednotky) a tím, co vidí a píše uživatel.
 *
 * Tohle je jediné místo v aplikaci, kde se pracuje s desetinnými čísly. Všude jinde
 * kolují celé haléře — jakmile by se částka někde cestou stala `Double`, začaly by
 * se rozjíždět součty.
 */
object MoneyFormat {

    /**
     * Počet desetinných míst měny. Většina měn má 2, ale ne všechny — japonský jen
     * a maďarský forint 0, takže natvrdo psaná dvojka by u nich částku stonásobně
     * nafoukla.
     */
    fun fractionDigits(currency: CurrencyCode): Int =
        runCatching { Currency.getInstance(currency.code).defaultFractionDigits }
            .getOrDefault(DEFAULT_FRACTION_DIGITS)
            .coerceAtLeast(0)

    /**
     * Přečte částku napsanou uživatelem.
     *
     * Zvládá čárku i tečku jako desetinný oddělovač a oddělovače tisíců včetně
     * pevné mezery, kterou vkládá české formátování. Když jsou v textu obojí,
     * desetinný je ten **poslední** — `1.234,56` i `1,234.56` tak dají totéž.
     *
     * Vrací [Result], protože rozepsaný vstup je při psaní běžný stav, ne chyba
     * hodná výjimky.
     */
    fun parse(text: String, currency: CurrencyCode): Result<Money> = runCatching {
        val decimal = DecimalInput.normalize(text, "Částka")
        require(decimal.signum() >= 0) { "Částka nesmí být záporná" }

        // HALF_UP: uživatel napsal víc desetinných míst, než měna má. Zaokrouhlit je
        // lepší než odmítnout — vstup z účtenky nebo z kalkulačky to dělá běžně.
        val minor = decimal
            .setScale(fractionDigits(currency), RoundingMode.HALF_UP)
            .movePointRight(fractionDigits(currency))
            .toBigIntegerExact()

        require(minor.bitLength() < Long.SIZE_BITS) { "Částka je mimo rozsah" }
        Money(minor.toLong())
    }

    /** Částka jako holé číslo bez měny — pro předvyplnění vstupního pole. */
    fun toEditableText(money: Money, currency: CurrencyCode): String =
        BigDecimal(money.minor).movePointLeft(fractionDigits(currency)).toPlainString()

    /** Částka včetně měny, ve tvaru obvyklém pro dané locale. */
    fun format(
        money: Money,
        currency: CurrencyCode,
        locale: Locale = Locale.getDefault(),
    ): String {
        val digits = fractionDigits(currency)
        val amount = BigDecimal(money.minor).movePointLeft(digits)

        val format = NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { this.currency = Currency.getInstance(currency.code) }
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return format.format(amount)
    }

    private const val DEFAULT_FRACTION_DIGITS = 2
}
