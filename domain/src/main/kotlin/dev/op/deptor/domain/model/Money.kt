package dev.op.deptor.domain.model

import kotlin.jvm.JvmInline

/**
 * ISO 4217 kód měny. Uchováváme jen kód — formátování a symbol řeší UI vrstva
 * podle locale zařízení.
 */
@JvmInline
value class CurrencyCode(val code: String) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) {
            "Kód měny musí být tři velká písmena, dostal jsem '$code'"
        }
    }

    override fun toString(): String = code

    companion object {
        val CZK = CurrencyCode("CZK")
        val EUR = CurrencyCode("EUR")
        val USD = CurrencyCode("USD")
    }
}

/**
 * Peněžní částka v nejmenší jednotce měny (haléře, centy).
 *
 * Záměrně `Long` a nikdy `Double`: celá aplikace stojí na opakovaném dělení částek
 * mezi členy a desetinná aritmetika by po pár operacích rozházela salda o haléře,
 * které by se pak nikdy nevyrovnaly na nulu.
 *
 * Částka může být záporná — používá se to pro salda (nedoplatek) a pro příjmy.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(minor + other.minor)

    operator fun minus(other: Money): Money = Money(minor - other.minor)

    operator fun times(factor: Long): Money = Money(minor * factor)

    operator fun unaryMinus(): Money = Money(-minor)

    val isZero: Boolean get() = minor == 0L
    val isPositive: Boolean get() = minor > 0L
    val isNegative: Boolean get() = minor < 0L

    fun abs(): Money = if (minor < 0) Money(-minor) else this

    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    override fun toString(): String = "$minor"

    companion object {
        val ZERO = Money(0)

        fun ofMinor(minor: Long): Money = Money(minor)
    }
}

fun Iterable<Money>.sum(): Money = Money(sumOf { it.minor })

fun <T> Iterable<T>.sumOfMoney(selector: (T) -> Money): Money =
    Money(sumOf { selector(it).minor })
