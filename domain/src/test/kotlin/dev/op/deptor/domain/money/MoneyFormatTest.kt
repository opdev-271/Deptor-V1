package dev.op.deptor.domain.money

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyFormatTest {

    private val czk = CurrencyCode.CZK
    private val jpy = CurrencyCode("JPY")

    private fun parse(text: String, currency: CurrencyCode = czk) =
        MoneyFormat.parse(text, currency)

    @Test
    fun `cele cislo`() {
        assertEquals(Money(90_000), parse("900").getOrThrow())
    }

    @Test
    fun `desetinna carka`() {
        assertEquals(Money(12_345), parse("123,45").getOrThrow())
    }

    @Test
    fun `desetinna tecka`() {
        assertEquals(Money(12_345), parse("123.45").getOrThrow())
    }

    @Test
    fun `jedno desetinne misto se doplni na dve`() {
        assertEquals(Money(12_350), parse("123,5").getOrThrow())
    }

    @Test
    fun `oddelovac tisicu mezerou`() {
        assertEquals(Money(123_456_00), parse("123 456").getOrThrow())
    }

    @Test
    fun `pevna mezera z formatovaneho vstupu`() {
        assertEquals(Money(123_456_00), parse("123 456").getOrThrow())
    }

    @Test
    fun `ceske formatovani s teckou jako oddelovacem tisicu`() {
        assertEquals(Money(123_456_78), parse("123.456,78").getOrThrow())
    }

    @Test
    fun `anglicke formatovani s carkou jako oddelovacem tisicu`() {
        assertEquals(Money(123_456_78), parse("123,456.78").getOrThrow())
    }

    @Test
    fun `posledni oddelovac je desetinny`() {
        // Klíčové pravidlo: rozhoduje pozice, ne který znak to je.
        assertEquals(Money(1_234_50), parse("1.234,5").getOrThrow())
        assertEquals(Money(1_234_50), parse("1,234.5").getOrThrow())
    }

    @Test
    fun `prebytecna desetinna mista se zaokrouhli`() {
        // Vstup z účtenky nebo kalkulačky tohle dělá běžně — odmítnout ho by bylo horší.
        assertEquals(Money(12_346), parse("123,456").getOrThrow())
        assertEquals(Money(12_345), parse("123,454").getOrThrow())
    }

    @Test
    fun `mena bez desetinnych mist`() {
        // JPY má 0 desetinných míst; kdyby se počítalo s dvěma, částka by byla stokrát vyšší.
        assertEquals(0, MoneyFormat.fractionDigits(jpy))
        assertEquals(Money(900), parse("900", jpy).getOrThrow())
        assertEquals(Money(901), parse("900,6", jpy).getOrThrow())
    }

    @Test
    fun `rozepsany vstup s carkou na konci`() {
        // Uživatel je uprostřed psaní — má vyjít 123, ne chyba.
        assertEquals(Money(12_300), parse("123,").getOrThrow())
    }

    @Test
    fun `nula`() {
        assertEquals(Money.ZERO, parse("0").getOrThrow())
        assertEquals(Money.ZERO, parse("0,00").getOrThrow())
    }

    @Test
    fun `prazdny vstup je chyba`() {
        assertTrue(parse("").isFailure)
        assertTrue(parse("   ").isFailure)
    }

    @Test
    fun `text neni castka`() {
        assertTrue(parse("abc").isFailure)
        assertTrue(parse("123 Kč").isFailure)
    }

    @Test
    fun `zaporna castka je chyba`() {
        // Směr transakce určuje její typ, ne znaménko částky.
        assertTrue(parse("-100").isFailure)
    }

    @Test
    fun `nesmyslny vstup neshodi aplikaci`() {
        listOf(",", ".", "..", ",,", "1.2.3.4,5,6", "--5", "1e10").forEach { input ->
            val result = parse(input)
            assertTrue(
                result.isFailure || result.getOrThrow().minor >= 0,
                "vstup '$input' skončil nečekaně: $result",
            )
        }
    }

    @Test
    fun `cesta tam a zpet pres editovatelny text`() {
        listOf(0L, 1L, 99L, 100L, 12_345L, 999_999_99L).forEach { minor ->
            val money = Money(minor)
            val text = MoneyFormat.toEditableText(money, czk)
            assertEquals(money, parse(text).getOrThrow(), "rozešlo se na '$text'")
        }
    }

    @Test
    fun `formatovana castka obsahuje cislo i menu`() {
        val formatted = MoneyFormat.format(Money(123_45), czk, java.util.Locale.forLanguageTag("cs-CZ"))
        assertTrue(formatted.contains("123"), "chybí částka: '$formatted'")
        assertTrue(formatted.contains("45"), "chybí haléře: '$formatted'")
        assertTrue(formatted.any { !it.isDigit() && !it.isWhitespace() }, "chybí měna: '$formatted'")
    }

    @Test
    fun `zaporne saldo se naformatuje`() {
        val formatted = MoneyFormat.format(Money(-30_000), czk, java.util.Locale.forLanguageTag("cs-CZ"))
        assertTrue(formatted.contains("300"), "chybí částka: '$formatted'")
    }
}
