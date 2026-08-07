package dev.op.deptor.domain.recurrence

import dev.op.deptor.domain.TestData.anna
import dev.op.deptor.domain.TestData.bob
import dev.op.deptor.domain.TestData.expense
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Money
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurrenceTest {

    private val zone = ZoneId.of("Europe/Prague")

    private fun recurrence(
        start: String = "2026-01-01",
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        generatedThrough: String? = null,
        active: Boolean = true,
    ) = Recurrence(
        id = RecurrenceId("najem"),
        groupId = GroupId("g1"),
        rule = RecurrenceRule(frequency, LocalDate.parse(start)),
        template = expense(payer = anna, amount = 1_500_000, between = listOf(anna, bob))
            .copy(description = "Nájem"),
        generatedThrough = generatedThrough?.let(LocalDate::parse),
        active = active,
    )

    @Test
    fun `vygeneruje transakci za kazdy dluzny termin`() {
        val result = recurrence().dueTransactions(LocalDate.parse("2026-03-10"), zone)

        assertEquals(3, result.size)
        assertTrue(result.all { it.description == "Nájem" })
        assertTrue(result.all { it.amount == Money(1_500_000) })
    }

    @Test
    fun `vzor se prenese vcetne rozuctovani`() {
        val source = recurrence()
        val generated = source.dueTransactions(LocalDate.parse("2026-01-05"), zone).single()

        assertEquals(source.template.payers, generated.payers)
        assertEquals(source.template.splits, generated.splits)
        assertEquals(source.template.currency, generated.currency)
    }

    @Test
    fun `id je odvozene z pravidla a terminu`() {
        val result = recurrence().dueTransactions(LocalDate.parse("2026-02-10"), zone)

        assertEquals("rec_najem_2026-01-01", result[0].id.value)
        assertEquals("rec_najem_2026-02-01", result[1].id.value)
    }

    @Test
    fun `dve generovani daji stejna id`() {
        // Na tom stojí ochrana proti duplicitám: dva členové můžou otevřít aplikaci
        // ve stejnou chvíli a oba pravidlo vygenerují.
        val first = recurrence().dueTransactions(LocalDate.parse("2026-03-10"), zone)
        val second = recurrence().dueTransactions(LocalDate.parse("2026-03-10"), zone)

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `datum transakce odpovida terminu i po prevodu na okamzik`() {
        val generated = recurrence().dueTransactions(LocalDate.parse("2026-01-05"), zone).single()

        // Poledne, ne půlnoc — jinak by posun zóny mohl datum přehodit o den zpět.
        assertEquals(
            LocalDate.parse("2026-01-01"),
            generated.date.atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `datum sedi i v zone za datovou hranici`() {
        val generated = recurrence().dueTransactions(
            LocalDate.parse("2026-01-05"),
            ZoneId.of("Pacific/Kiritimati"),
        ).single()

        assertEquals(
            LocalDate.parse("2026-01-01"),
            generated.date.atZone(ZoneId.of("Pacific/Kiritimati")).toLocalDate(),
        )
    }

    @Test
    fun `uz vygenerovane terminy se neopakuji`() {
        val result = recurrence(generatedThrough = "2026-02-01")
            .dueTransactions(LocalDate.parse("2026-03-10"), zone)

        assertEquals(1, result.size)
        assertEquals("rec_najem_2026-03-01", result.single().id.value)
    }

    @Test
    fun `zrusene pravidlo negeneruje nic`() {
        val result = recurrence(active = false).dueTransactions(LocalDate.parse("2026-12-31"), zone)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `generovanou transakci lze poznat podle id`() {
        val generated = recurrence().dueTransactions(LocalDate.parse("2026-01-05"), zone).single()

        assertTrue(Recurrence.isGenerated(generated.id))
        assertTrue(!Recurrence.isGenerated(dev.op.deptor.domain.model.TransactionId("bezne")))
    }

    @Test
    fun `vygenerovane transakce jsou platne`() {
        // Konstruktor Transaction hlídá invariant součtů; kdyby ho kopie porušila,
        // spadlo by to tady, ne až v produkci.
        val result = recurrence().dueTransactions(LocalDate.parse("2026-06-10"), zone)

        result.forEach { transaction ->
            assertEquals(transaction.amount.minor, transaction.splits.sumOf { it.amount.minor })
            assertEquals(transaction.amount.minor, transaction.payers.sumOf { it.amount.minor })
        }
    }
}
