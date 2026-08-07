package dev.op.deptor.domain.money

import java.math.BigDecimal

/**
 * Převod toho, co uživatel napíše, na desetinné číslo.
 *
 * Sdílí ho [MoneyFormat] i [PercentFormat] — obojí řeší tentýž problém: uživatel může
 * psát čárku i tečku, vkládat text ze schránky i s oddělovači tisíců, a výsledek musí
 * skončit jako přesná hodnota, ne jako `Double`.
 */
internal object DecimalInput {

    /**
     * Znormalizuje vstup na tvar, který přijme [BigDecimal].
     *
     * Zvládá čárku i tečku jako desetinný oddělovač a oddělovače tisíců včetně pevné
     * mezery z českého formátování a apostrofu ze švýcarského. Když jsou v textu obojí,
     * desetinný je ten **poslední** — `1.234,56` i `1,234.56` tak dají totéž.
     *
     * @throws IllegalArgumentException u prázdného nebo nečíselného vstupu.
     */
    fun normalize(text: String, label: String): BigDecimal {
        val cleaned = text.filterNot {
            it.isWhitespace() || it == NON_BREAKING_SPACE || it == NARROW_NON_BREAKING_SPACE || it == '\''
        }

        require(cleaned.isNotEmpty()) { "$label není vyplněn" }
        require(cleaned.none { it.isLetter() }) { "$label obsahuje písmena: '$text'" }

        val lastSeparator = maxOf(cleaned.lastIndexOf(','), cleaned.lastIndexOf('.'))
        val normalized = if (lastSeparator < 0) {
            cleaned
        } else {
            val whole = cleaned.substring(0, lastSeparator).replace(",", "").replace(".", "")
            val fraction = cleaned.substring(lastSeparator + 1)
            require(fraction.all { it.isDigit() }) { "$label má neplatnou desetinnou část: '$text'" }
            if (fraction.isEmpty()) whole else "$whole.$fraction"
        }

        require(normalized.isNotEmpty()) { "$label není platné číslo: '$text'" }
        return BigDecimal(normalized)
    }

    private const val NON_BREAKING_SPACE = '\u00A0'
    private const val NARROW_NON_BREAKING_SPACE = '\u202F'
}
