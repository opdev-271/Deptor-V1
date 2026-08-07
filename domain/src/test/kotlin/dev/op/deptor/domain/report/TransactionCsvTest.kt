package dev.op.deptor.domain.report

import dev.op.deptor.domain.TestData.anna
import dev.op.deptor.domain.TestData.bob
import dev.op.deptor.domain.TestData.cyril
import dev.op.deptor.domain.TestData.expense
import dev.op.deptor.domain.TestData.settlement
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.UserId
import dev.op.deptor.domain.money.ExchangeRate
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionCsvTest {

    private val everyone = listOf(anna, bob, cyril)

    private val group = Group(
        id = GroupId("g1"),
        name = "Výlet",
        currency = CurrencyCode.CZK,
        ownerUid = UserId("uid-anna"),
        members = listOf(
            Member(anna, "Anna", UserId("uid-anna")),
            Member(bob, "Bob"),
            Member(cyril, "Cyril"),
        ),
    )

    private fun csv(vararg transactions: Transaction) =
        TransactionCsv.build(group, transactions.toList(), ZoneId.of("UTC"))

    private fun rows(text: String) = text.trim().split("\r\n")

    @Test
    fun `prazdny export ma jen hlavicku`() {
        assertEquals(1, rows(csv()).size)
    }

    @Test
    fun `hlavicka obsahuje vsechny sloupce`() {
        val header = rows(csv()).first().split(';')
        assertTrue("datum" in header)
        assertTrue("castka" in header)
        assertTrue("mena" in header)
        assertTrue("podily" in header)
    }

    @Test
    fun `radek obsahuje castku i jmena`() {
        val row = rows(csv(expense(payer = anna, amount = 90_000, between = everyone)))[1]

        assertTrue(row.contains("900,00"), "chybí částka: $row")
        assertTrue(row.contains("Anna"), "chybí jméno plátce: $row")
        assertTrue(row.contains("Bob"), "chybí jméno v podílech: $row")
    }

    @Test
    fun `oddelovac je stredník a desetinna carka`() {
        // S čárkou jako oddělovačem by se soubor rozpadl na první částce.
        val row = rows(csv(expense(payer = anna, amount = 12_345, between = listOf(anna))))[1]
        assertTrue(row.contains("123,45"))
        assertTrue(row.contains(";"))
    }

    @Test
    fun `stredník v popisu se obali uvozovkami`() {
        val transaction = expense(payer = anna, amount = 100, between = listOf(anna))
            .copy(description = "oběd; večeře")
        val row = rows(csv(transaction))[1]

        assertTrue(row.contains("\"oběd; večeře\""), "středník nerozbil sloupce? $row")
    }

    @Test
    fun `uvozovky v popisu se zdvoji`() {
        val transaction = expense(payer = anna, amount = 100, between = listOf(anna))
            .copy(description = "pizza \"quattro\"")
        val row = rows(csv(transaction))[1]

        assertTrue(row.contains("\"pizza \"\"quattro\"\"\""), "špatně escapované uvozovky: $row")
    }

    @Test
    fun `konec radku v popisu nerozbije soubor`() {
        val transaction = expense(payer = anna, amount = 100, between = listOf(anna))
            .copy(description = "první\ndruhý")

        // Řádků musí zůstat tolik jako transakcí plus hlavička.
        assertEquals(2, rows(csv(transaction)).size)
    }

    @Test
    fun `popis zacinajici rovnitkem se neprovede jako vzorec`() {
        // Popis píše kdokoli ze skupiny; bez ochrany by přes export šlo spustit
        // cizímu člověku vzorec v Excelu.
        val transaction = expense(payer = anna, amount = 100, between = listOf(anna))
            .copy(description = "=SUM(A1:A9)")
        val row = rows(csv(transaction))[1]

        assertTrue(row.contains("'=SUM(A1:A9)"), "vzorec není zneškodněný: $row")
    }

    @Test
    fun `dalsi nebezpecne zacatky jsou taky osetrene`() {
        listOf("+1", "-1+2", "@SUM(1)").forEach { description ->
            val transaction = expense(payer = anna, amount = 100, between = listOf(anna))
                .copy(description = description)
            val row = rows(csv(transaction))[1]
            assertTrue(row.contains("'$description"), "nezneškodněno: $row")
        }
    }

    @Test
    fun `bezny popis se zbytecne nezavorkuje`() {
        val transaction = expense(payer = anna, amount = 100, between = listOf(anna))
            .copy(description = "oběd")
        val row = rows(csv(transaction))[1]

        assertFalse(row.contains("\"oběd\""), "zbytečné uvozovky: $row")
        assertFalse(row.contains("'oběd"), "zbytečný apostrof: $row")
    }

    @Test
    fun `cizi mena nese puvodni castku i prepocet`() {
        val transaction = expense(payer = anna, amount = 10_000, between = listOf(anna))
            .copy(currency = CurrencyCode.EUR, exchangeRate = ExchangeRate(BigDecimal("25")))
        val row = rows(csv(transaction))[1]

        assertTrue(row.contains("EUR"), "chybí původní měna: $row")
        assertTrue(row.contains("100,00"), "chybí původní částka: $row")
        assertTrue(row.contains("2500,00"), "chybí přepočet: $row")
        assertTrue(row.contains("25"), "chybí kurz: $row")
    }

    @Test
    fun `kategorie se exportuje jako id`() {
        val transaction = expense(payer = anna, amount = 100, between = listOf(anna))
            .copy(categoryId = ExpenseCategory.FOOD.id)

        // Id, ne přeložený název — export má být strojově čitelný.
        assertTrue(rows(csv(transaction))[1].contains("food"))
    }

    @Test
    fun `vyrovnani se exportuje taky`() {
        val row = rows(csv(settlement(from = bob, to = anna, amount = 300)))[1]
        assertTrue(row.contains("SETTLEMENT"))
    }

    @Test
    fun `pocet radku odpovida poctu transakci`() {
        val text = csv(
            expense(payer = anna, amount = 100, between = everyone),
            expense(payer = bob, amount = 200, between = everyone),
            settlement(from = cyril, to = anna, amount = 50),
        )
        assertEquals(4, rows(text).size)
    }

    @Test
    fun `nazev souboru je bezpecny`() {
        assertEquals("deptor-Vylet.csv", TransactionCsv.fileName(group.copy(name = "Vylet")))
        assertEquals("deptor-a-b.csv", TransactionCsv.fileName(group.copy(name = "a/b")))
        assertEquals("deptor-skupina.csv", TransactionCsv.fileName(group.copy(name = "///")))
    }

    @Test
    fun `kazdy radek ma stejny pocet sloupcu`() {
        val text = csv(
            expense(payer = anna, amount = 100, between = everyone)
                .copy(description = "s; středníkem a \"uvozovkou\""),
            settlement(from = bob, to = anna, amount = 50),
        )

        // Počítá se mimo uvozovky — escapované oddělovače se nesmí počítat.
        val counts = rows(text).map { line ->
            var inQuotes = false
            line.count { char ->
                if (char == '"') inQuotes = !inQuotes
                char == ';' && !inQuotes
            }
        }
        assertEquals(1, counts.toSet().size, "řádky mají různý počet sloupců: $counts")
    }
}
