package dev.op.deptor.domain.balance

import dev.op.deptor.domain.TestData.anna
import dev.op.deptor.domain.TestData.bob
import dev.op.deptor.domain.TestData.cyril
import dev.op.deptor.domain.TestData.expense
import dev.op.deptor.domain.TestData.income
import dev.op.deptor.domain.TestData.settlement
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BalancesTest {

    private val everyone = listOf(anna, bob, cyril)

    private fun balancesOf(vararg transactions: dev.op.deptor.domain.model.Transaction) =
        computeBalances(transactions.toList(), everyone, CurrencyCode.CZK)
            .associate { it.memberId to it.amount }

    @Test
    fun `bez transakci jsou vsichni na nule`() {
        val balances = computeBalances(emptyList(), everyone, CurrencyCode.CZK)
        assertEquals(3, balances.size)
        assertTrue(balances.all { it.isSettled })
    }

    @Test
    fun `anna plati obed pro tri`() {
        // Anna zaplatí 900, každý spotřebuje 300 → Anna +600, ostatní -300.
        val balances = balancesOf(expense(payer = anna, amount = 900, between = everyone))

        assertEquals(Money(600), balances.getValue(anna))
        assertEquals(Money(-300), balances.getValue(bob))
        assertEquals(Money(-300), balances.getValue(cyril))
    }

    @Test
    fun `soucet sald je vzdy nula`() {
        val balances = balancesOf(
            expense(payer = anna, amount = 900, between = everyone),
            expense(payer = bob, amount = 451, between = everyone),
            expense(payer = cyril, amount = 1_007, between = listOf(anna, cyril)),
        )
        assertEquals(0L, balances.values.sumOf { it.minor })
    }

    @Test
    fun `vyrovnani snizi dluh`() {
        val balances = balancesOf(
            expense(payer = anna, amount = 900, between = everyone),
            settlement(from = bob, to = anna, amount = 300),
        )
        assertEquals(Money(300), balances.getValue(anna))
        assertEquals(Money.ZERO, balances.getValue(bob))
        assertEquals(Money(-300), balances.getValue(cyril))
    }

    @Test
    fun `vice platcu na jednom vydaji`() {
        val balances = balancesOf(
            expense(
                payers = listOf(anna to 600L, bob to 300L),
                amount = 900,
                between = everyone,
            ),
        )
        assertEquals(Money(300), balances.getValue(anna))
        assertEquals(Money.ZERO, balances.getValue(bob))
        assertEquals(Money(-300), balances.getValue(cyril))
    }

    @Test
    fun `prijem obraci smer`() {
        // Anna převzala vratku 900 za všechny → dluží skupině 600, ostatní +300.
        val balances = balancesOf(income(receiver = anna, amount = 900, between = everyone))

        assertEquals(Money(-600), balances.getValue(anna))
        assertEquals(Money(300), balances.getValue(bob))
        assertEquals(Money(300), balances.getValue(cyril))
    }

    @Test
    fun `novy clen bez transakci je ve vysledku s nulou`() {
        val david = MemberId("d-david")
        val balances = computeBalances(
            listOf(expense(payer = anna, amount = 900, between = everyone)),
            everyone + david,
            CurrencyCode.CZK,
        )
        assertEquals(Money.ZERO, balances.first { it.memberId == david }.amount)
    }

    @Test
    fun `byvaly clen s transakci se ve vysledku objevi taky`() {
        // Člen byl ze skupiny odebrán, ale pořád má nevyrovnané saldo — nesmí zmizet.
        val balances = computeBalances(
            listOf(expense(payer = anna, amount = 900, between = everyone)),
            listOf(anna, bob),
            CurrencyCode.CZK,
        )
        assertEquals(Money(-300), balances.first { it.memberId == cyril }.amount)
    }

    @Test
    fun `transakce bez kurzu se do ciziho salda zapocitat neda`() {
        // Transakce v CZK bez kurzu se do eurového salda převést nedá. Mlčky ji
        // vzít jako 1:1 by saldo tiše rozbilo, takže je to hlasitá chyba.
        assertFailsWith<IllegalArgumentException> {
            computeBalances(
                listOf(expense(payer = anna, amount = 900, between = everyone)),
                everyone,
                CurrencyCode.EUR,
            )
        }
    }

    @Test
    fun `involving vrati jen transakce daneho clena`() {
        val annaBobLunch = expense(payer = anna, amount = 200, between = listOf(anna, bob))
        val everyoneDinner = expense(payer = cyril, amount = 900, between = everyone)

        val cyrilsTransactions = listOf(annaBobLunch, everyoneDinner).involving(cyril)
        assertEquals(listOf(everyoneDinner), cyrilsTransactions)
    }
}
