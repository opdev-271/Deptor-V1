package dev.op.deptor.domain.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PercentFormatTest {

    private fun parse(text: String) = PercentFormat.parseBasisPoints(text)

    @Test
    fun `cele procento`() {
        assertEquals(5_000, parse("50").getOrThrow())
        assertEquals(PercentFormat.FULL, parse("100").getOrThrow())
    }

    @Test
    fun `desetinne procento carkou i teckou`() {
        assertEquals(3_333, parse("33,33").getOrThrow())
        assertEquals(3_333, parse("33.33").getOrThrow())
    }

    @Test
    fun `jedno desetinne misto`() {
        assertEquals(1_250, parse("12,5").getOrThrow())
    }

    @Test
    fun `prebytecna desetinna mista se zaokrouhli`() {
        // Jemněji než na setinu procenta se rozúčtovat stejně nedá.
        assertEquals(3_333, parse("33,333").getOrThrow())
        assertEquals(3_334, parse("33,336").getOrThrow())
    }

    @Test
    fun `nula`() {
        assertEquals(0, parse("0").getOrThrow())
    }

    @Test
    fun `rozepsany vstup s carkou na konci`() {
        assertEquals(3_300, parse("33,").getOrThrow())
    }

    @Test
    fun `vic nez sto procent je chyba`() {
        assertTrue(parse("101").isFailure)
        assertTrue(parse("100,01").isFailure)
    }

    @Test
    fun `zaporne procento je chyba`() {
        assertTrue(parse("-10").isFailure)
    }

    @Test
    fun `prazdny a nesmyslny vstup je chyba`() {
        assertTrue(parse("").isFailure)
        assertTrue(parse("   ").isFailure)
        assertTrue(parse("abc").isFailure)
        assertTrue(parse("50 %").isFailure)
    }

    @Test
    fun `tri tretiny procent daji presne sto`() {
        // Tohle je důvod, proč se procenta drží jako celá čísla: 33,33 + 33,33 + 33,34
        // musí dát přesně 100 %, jinak by rozúčtování o haléře nesedělo.
        val parts = listOf("33,33", "33,33", "33,34").map { parse(it).getOrThrow() }
        assertEquals(PercentFormat.FULL, parts.sum())
    }

    @Test
    fun `cesta tam a zpet`() {
        listOf(0, 1, 250, 3_333, 5_000, PercentFormat.FULL).forEach { basisPoints ->
            val text = PercentFormat.toEditableText(basisPoints)
            assertEquals(basisPoints, parse(text).getOrThrow(), "rozešlo se na '$text'")
        }
    }

    @Test
    fun `formatovani je bez zbytecnych nul`() {
        assertEquals("50 %", PercentFormat.format(5_000))
        assertEquals("33,33 %".replace(',', '.'), PercentFormat.format(3_333))
    }
}
