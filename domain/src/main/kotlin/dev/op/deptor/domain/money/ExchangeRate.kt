package dev.op.deptor.domain.money

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Kurz z měny transakce na měnu skupiny.
 *
 * Kurz se ukládá k transakci a **nepřepočítává se později**. Kdyby se salda počítala
 * vždy aktuálním kurzem, pohnul by se i dluh, který spolu lidé už dávno vyrovnali —
 * a částka, na které se domluvili, by po týdnu seděla jinak. Uložený kurz je záznam
 * o tom, jak se to počítalo v okamžiku zadání.
 */
@JvmInline
value class ExchangeRate(val value: BigDecimal) {

    init {
        require(value.signum() > 0) { "Kurz musí být kladný, dostal jsem $value" }
    }

    val isIdentity: Boolean get() = value.compareTo(BigDecimal.ONE) == 0

    /**
     * Převede částku do cílové měny.
     *
     * Počítá se přes [BigDecimal] a zaokrouhluje až na konci na nejmenší jednotku
     * cílové měny — mezivýsledek se nikdy nestane `Double`.
     */
    fun convert(amount: Money, from: CurrencyCode, to: CurrencyCode): Money {
        if (from == to && isIdentity) return amount

        val sourceDigits = MoneyFormat.fractionDigits(from)
        val targetDigits = MoneyFormat.fractionDigits(to)

        val converted = BigDecimal(amount.minor)
            .movePointLeft(sourceDigits)
            .multiply(value)
            .setScale(targetDigits, RoundingMode.HALF_UP)
            .movePointRight(targetDigits)
            .toBigIntegerExact()

        require(converted.bitLength() < Long.SIZE_BITS) { "Převedená částka je mimo rozsah" }
        return Money(converted.toLong())
    }

    override fun toString(): String = value.toPlainString()

    companion object {
        /** Kurz 1:1 — transakce je ve stejné měně jako skupina. */
        val IDENTITY = ExchangeRate(BigDecimal.ONE)

        /**
         * Přečte kurz z textu. Používá se při čtení z databáze i při ručním zadání,
         * proto přijímá čárku i tečku jako desetinný oddělovač.
         */
        fun parse(text: String): Result<ExchangeRate> = runCatching {
            ExchangeRate(DecimalInput.normalize(text, "Kurz"))
        }
    }
}
