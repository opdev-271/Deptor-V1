package dev.op.deptor.domain.report

import dev.op.deptor.domain.TestData.anna
import dev.op.deptor.domain.TestData.bob
import dev.op.deptor.domain.TestData.cyril
import dev.op.deptor.domain.TestData.expense
import dev.op.deptor.domain.TestData.income
import dev.op.deptor.domain.TestData.settlement
import dev.op.deptor.domain.model.CategoryId
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.money.ExchangeRate
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryTotalsTest {

    private val everyone = listOf(anna, bob, cyril)

    private fun totals(vararg transactions: Transaction) =
        categoryTotals(transactions.toList(), CurrencyCode.CZK)

    private fun categorized(amount: Long, category: ExpenseCategory) =
        expense(payer = anna, amount = amount, between = everyone)
            .copy(categoryId = category.id)

    @Test
    fun `bez transakci je prehled prazdny`() {
        assertTrue(totals().isEmpty())
    }

    @Test
    fun `secte vydaje ve stejne kategorii`() {
        val result = totals(
            categorized(300, ExpenseCategory.FOOD),
            categorized(200, ExpenseCategory.FOOD),
        )

        assertEquals(1, result.size)
        assertEquals(ExpenseCategory.FOOD, result.single().category)
        assertEquals(Money(500), result.single().total)
        assertEquals(10_000, result.single().shareBasisPoints)
    }

    @Test
    fun `radi od nejvetsi utraty`() {
        val result = totals(
            categorized(100, ExpenseCategory.FOOD),
            categorized(900, ExpenseCategory.TRANSPORT),
            categorized(500, ExpenseCategory.SHOPPING),
        )

        assertEquals(
            listOf(ExpenseCategory.TRANSPORT, ExpenseCategory.SHOPPING, ExpenseCategory.FOOD),
            result.map { it.category },
        )
    }

    @Test
    fun `podily davaji presne sto procent`() {
        // Tři stejné částky se na 100 % beze zbytku nerozdělí — přehled to musí ustát.
        val result = totals(
            categorized(100, ExpenseCategory.FOOD),
            categorized(100, ExpenseCategory.TRANSPORT),
            categorized(100, ExpenseCategory.SHOPPING),
        )

        assertEquals(10_000, result.sumOf { it.shareBasisPoints })
    }

    @Test
    fun `vydaj bez kategorie ma vlastni radek`() {
        val result = totals(
            categorized(300, ExpenseCategory.FOOD),
            expense(payer = anna, amount = 700, between = everyone),
        )

        val uncategorized = result.first { it.category == null }
        assertEquals(Money(700), uncategorized.total)
    }

    @Test
    fun `nezarazene jde na konec i pri shode castek`() {
        val result = totals(
            expense(payer = anna, amount = 500, between = everyone),
            categorized(500, ExpenseCategory.FOOD),
        )

        assertNull(result.last().category, "nezařazené patří na konec: $result")
    }

    @Test
    fun `vyrovnani se do utraty nepocita`() {
        // Vyrovnání je přesun peněz, ne útrata. Kdyby se počítalo, „utratili jsme"
        // by rostlo pokaždé, když si někdo pošle dluh zpátky.
        val result = totals(
            categorized(500, ExpenseCategory.FOOD),
            settlement(from = bob, to = anna, amount = 300),
        )

        assertEquals(1, result.size)
        assertEquals(Money(500), result.single().total)
    }

    @Test
    fun `prijem se do utraty nepocita`() {
        val result = totals(
            categorized(500, ExpenseCategory.FOOD),
            income(receiver = anna, amount = 300, between = everyone),
        )

        assertEquals(Money(500), result.single().total)
    }

    @Test
    fun `cizi mena se do prehledu zapocita prepoctena`() {
        val foreign = expense(payer = anna, amount = 10_000, between = everyone)
            .copy(
                currency = CurrencyCode.EUR,
                categoryId = ExpenseCategory.TRANSPORT.id,
                exchangeRate = ExchangeRate(BigDecimal("25")),
            )

        val result = categoryTotals(listOf(foreign), CurrencyCode.CZK)
        assertEquals(Money(250_000), result.single().total)
    }

    @Test
    fun `nezname id kategorie se chova jako nezarazeny vydaj`() {
        // Novější verze aplikace může zapsat kategorii, kterou tahle nezná —
        // spadnout kvůli tomu by bylo horší než ji ukázat jako nezařazenou.
        val result = totals(
            categorized(300, ExpenseCategory.FOOD),
            expense(payer = anna, amount = 200, between = everyone)
                .copy(categoryId = CategoryId("neco-noveho")),
        )

        assertEquals(Money(200), result.first { it.category == null }.total)
    }

    @Test
    fun `soucet kategorii odpovida celkove utrate`() {
        val random = Random(11)
        repeat(300) {
            val transactions = List(random.nextInt(1, 8)) {
                val amount = random.nextLong(1, 200_000)
                if (random.nextBoolean()) {
                    categorized(amount, ExpenseCategory.entries[random.nextInt(ExpenseCategory.entries.size)])
                } else {
                    expense(payer = anna, amount = amount, between = everyone)
                }
            }

            val result = categoryTotals(transactions, CurrencyCode.CZK)
            val expected = transactions.sumOf { it.amount.minor }

            assertEquals(expected, result.sumOf { it.total.minor }, "součet nesedí u $transactions")
            assertEquals(10_000, result.sumOf { it.shareBasisPoints }, "podíly nedaly 100 %")
        }
    }

    @Test
    fun `stabilni id prezije prejmenovani konstanty`() {
        // Ukládá se id, ne pořadí v enumu — jinak by přidání kategorie doprostřed
        // přeznačilo už uložené výdaje.
        assertEquals(ExpenseCategory.FOOD, ExpenseCategory.fromId(CategoryId("food")))
        assertNull(ExpenseCategory.fromId(null))
        assertNull(ExpenseCategory.fromId(CategoryId("neexistuje")))
    }
}
