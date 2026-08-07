package dev.op.deptor.domain.recurrence

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecurrenceRuleTest {

    private fun date(text: String) = LocalDate.parse(text)

    private fun rule(
        frequency: RecurrenceFrequency,
        start: String,
        interval: Int = 1,
        end: String? = null,
    ) = RecurrenceRule(frequency, date(start), interval, end?.let(::date))

    // --- výpočet termínů ---

    @Test
    fun `denni opakovani`() {
        val rule = rule(RecurrenceFrequency.DAILY, "2026-01-01")
        assertEquals(date("2026-01-01"), rule.occurrence(0))
        assertEquals(date("2026-01-04"), rule.occurrence(3))
    }

    @Test
    fun `tydenni opakovani`() {
        val rule = rule(RecurrenceFrequency.WEEKLY, "2026-01-05")
        assertEquals(date("2026-01-19"), rule.occurrence(2))
    }

    @Test
    fun `mesicni opakovani`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-01-15")
        assertEquals(date("2026-03-15"), rule.occurrence(2))
    }

    @Test
    fun `interval znamena kazde N-te obdobi`() {
        val ctvrtletne = rule(RecurrenceFrequency.MONTHLY, "2026-01-10", interval = 3)
        assertEquals(date("2026-04-10"), ctvrtletne.occurrence(1))
        assertEquals(date("2026-07-10"), ctvrtletne.occurrence(2))
    }

    @Test
    fun `najem od 31 ledna se vraci na 31 v mesicich ktere ho maji`() {
        // Tohle je ta past: kdyby se přičítalo k předchozímu termínu, únor by datum
        // ořízl na 28. a od té chvíle by nájem chodil 28. každý měsíc.
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-01-31")

        assertEquals(date("2026-01-31"), rule.occurrence(0))
        assertEquals(date("2026-02-28"), rule.occurrence(1))
        assertEquals(date("2026-03-31"), rule.occurrence(2))
        assertEquals(date("2026-04-30"), rule.occurrence(3))
        assertEquals(date("2026-05-31"), rule.occurrence(4))
    }

    @Test
    fun `29 unora se pri rocnim opakovani vraci v prestupnem roce`() {
        val rule = rule(RecurrenceFrequency.YEARLY, "2024-02-29")

        assertEquals(date("2025-02-28"), rule.occurrence(1))
        assertEquals(date("2028-02-29"), rule.occurrence(4))
    }

    // --- dlužné termíny ---

    @Test
    fun `nove pravidlo vygeneruje vsechny termíny od zacatku`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-01-01")
        val due = rule.dueOccurrences(today = date("2026-03-15"))

        assertEquals(listOf("2026-01-01", "2026-02-01", "2026-03-01").map(::date), due)
    }

    @Test
    fun `uz vygenerovane terminy se neopakuji`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-01-01")
        val due = rule.dueOccurrences(
            today = date("2026-03-15"),
            generatedThrough = date("2026-02-01"),
        )

        assertEquals(listOf(date("2026-03-01")), due)
    }

    @Test
    fun `budouci terminy se negeneruji`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-01-01")
        val due = rule.dueOccurrences(today = date("2026-01-15"))

        assertEquals(listOf(date("2026-01-01")), due)
    }

    @Test
    fun `pravidlo ktere jeste nezacalo negeneruje nic`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-06-01")
        assertTrue(rule.dueOccurrences(today = date("2026-01-01")).isEmpty())
    }

    @Test
    fun `po konci opakovani se uz nic negeneruje`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-01-01", end = "2026-03-01")
        val due = rule.dueOccurrences(today = date("2026-12-31"))

        assertEquals(3, due.size)
        assertEquals(date("2026-03-01"), due.last())
    }

    @Test
    fun `zameskana obdobi se dozenou`() {
        // Aplikace se dva měsíce neotevřela — nájem musí dorazit za oba.
        val rule = rule(RecurrenceFrequency.MONTHLY, "2026-01-01")
        val due = rule.dueOccurrences(
            today = date("2026-04-05"),
            generatedThrough = date("2026-01-01"),
        )

        assertEquals(listOf("2026-02-01", "2026-03-01", "2026-04-01").map(::date), due)
    }

    @Test
    fun `dlouho stare pravidlo nevysype vsechno naraz`() {
        // Denní opakování od roku 2020 by jinak při prvním otevření vytvořilo
        // tisíce transakcí najednou.
        val rule = rule(RecurrenceFrequency.DAILY, "2020-01-01")
        val due = rule.dueOccurrences(today = date("2026-08-07"))

        assertTrue(due.size <= 120, "vygenerovalo se ${due.size} termínů naráz")
        assertEquals(date("2020-01-01"), due.first())
    }

    @Test
    fun `dohanenie pokracuje pri dalsim spusteni`() {
        val rule = rule(RecurrenceFrequency.DAILY, "2020-01-01")

        val first = rule.dueOccurrences(today = date("2026-08-07"))
        val second = rule.dueOccurrences(
            today = date("2026-08-07"),
            generatedThrough = first.last(),
        )

        assertTrue(second.isNotEmpty(), "druhý běh musí pokračovat tam, kde první skončil")
        assertTrue(second.first().isAfter(first.last()))
    }

    @Test
    fun `terminy jsou vzdy vzestupne a bez duplicit`() {
        val rule = rule(RecurrenceFrequency.WEEKLY, "2026-01-01", interval = 2)
        val due = rule.dueOccurrences(today = date("2026-06-01"))

        assertEquals(due.sorted(), due)
        assertEquals(due.size, due.toSet().size)
    }

    // --- neplatná pravidla ---

    @Test
    fun `nulovy nebo zaporny interval je chyba`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrenceRule(RecurrenceFrequency.DAILY, date("2026-01-01"), interval = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RecurrenceRule(RecurrenceFrequency.DAILY, date("2026-01-01"), interval = -1)
        }
    }

    @Test
    fun `konec pred zacatkem je chyba`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrenceRule(
                RecurrenceFrequency.DAILY,
                date("2026-05-01"),
                endDate = date("2026-01-01"),
            )
        }
    }
}
