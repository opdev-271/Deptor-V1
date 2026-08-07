package dev.op.deptor.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Procenta jako celá čísla v setinách procenta (basis points): 33,33 % = 3333.
 *
 * Desetinná procenta by se nedala sečíst přesně na 100 % a rozúčtování by pak
 * o haléře nesedělo. Sto procent je proto vždy přesně [FULL].
 */
object PercentFormat {

    /** 100 % v setinách procenta. */
    const val FULL = 10_000

    /**
     * Přečte procento napsané uživatelem. Přijímá čárku i tečku.
     *
     * Víc než dvě desetinná místa se zaokrouhlí — jemněji než na setinu procenta
     * se rozúčtovat stejně nedá.
     */
    fun parseBasisPoints(text: String): Result<Int> = runCatching {
        val decimal = DecimalInput.normalize(text, "Procento")
        require(decimal.signum() >= 0) { "Procento nesmí být záporné" }

        val basisPoints = decimal
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .toBigIntegerExact()

        require(basisPoints <= FULL.toBigInteger()) { "Procento nesmí být větší než 100" }
        basisPoints.toInt()
    }

    /** Procento pro předvyplnění vstupního pole, bez zbytečných nul. */
    fun toEditableText(basisPoints: Int): String =
        BigDecimal(basisPoints).movePointLeft(2).stripTrailingZeros().toPlainString()

    /** Procento k zobrazení, například „33,33 %“. */
    fun format(basisPoints: Int): String = "${toEditableText(basisPoints)} %"
}
