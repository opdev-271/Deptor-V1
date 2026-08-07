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
import dev.op.deptor.domain.model.Transaction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairwiseDebtsTest {

    private val everyone = listOf(anna, bob, cyril)

    private fun debts(vararg transactions: Transaction) =
        computePairwiseDebts(transactions.toList(), CurrencyCode.CZK)

    private fun List<PairwiseDebt>.between(from: MemberId, to: MemberId): Long =
        firstOrNull { it.from == from && it.to == to }?.amount?.minor ?: 0L

    @Test
    fun `bez transakci nikdo nikomu nedluzi`() {
        assertTrue(debts().isEmpty())
    }

    @Test
    fun `anna plati obed pro tri`() {
        val result = debts(expense(payer = anna, amount = 900, between = everyone))

        // Anna zaplatila celý účet, takže jí Bob i Cyril dluží svůj podíl.
        assertEquals(300, result.between(bob, anna))
        assertEquals(300, result.between(cyril, anna))
        // Anna sama sobě nedluží nic.
        assertEquals(0, result.between(anna, anna))
        assertEquals(2, result.size)
    }

    @Test
    fun `podil se rozpocita mezi platce v pomeru vkladu`() {
        // Anna vložila 600, Bob 300 → Cyrilův podíl 300 dluží ze dvou třetin Anně.
        val result = debts(
            expense(payers = listOf(anna to 600L, bob to 300L), amount = 900, between = everyone),
        )

        assertEquals(200, result.between(cyril, anna))
        assertEquals(100, result.between(cyril, bob))
        // Bob za sebe vložil 300 a spotřeboval 300; Anně dluží dvě třetiny svého podílu,
        // ale zároveň mu Anna dluží třetinu svého — po vynetování zbývá 100.
        assertEquals(100, result.between(bob, anna))
    }

    @Test
    fun `vzajemne dluhy se vynetuji`() {
        // Anna zaplatí za Boba 300, Bob zaplatí za Annu 100 → zbývá jediný dluh 200.
        val result = debts(
            expense(payer = anna, amount = 300, between = listOf(bob)),
            expense(payer = bob, amount = 100, between = listOf(anna)),
        )

        assertEquals(1, result.size)
        assertEquals(200, result.between(bob, anna))
        assertEquals(0, result.between(anna, bob))
    }

    @Test
    fun `uplne vyrovnany par v seznamu neni`() {
        val result = debts(
            expense(payer = anna, amount = 300, between = listOf(bob)),
            expense(payer = bob, amount = 300, between = listOf(anna)),
        )
        assertTrue(result.isEmpty(), "vyrovnaný pár se nemá zobrazovat: $result")
    }

    @Test
    fun `vyrovnani dluh smaze`() {
        val result = debts(
            expense(payer = anna, amount = 900, between = everyone),
            settlement(from = bob, to = anna, amount = 300),
        )

        assertEquals(0, result.between(bob, anna))
        assertEquals(300, result.between(cyril, anna))
        assertEquals(1, result.size)
    }

    @Test
    fun `castecne vyrovnani snizi dluh`() {
        val result = debts(
            expense(payer = anna, amount = 900, between = everyone),
            settlement(from = bob, to = anna, amount = 100),
        )
        assertEquals(200, result.between(bob, anna))
    }

    @Test
    fun `retez dluhu se NEzkracuje`() {
        // Rozdíl proti simplify: Anna zaplatila za Boba, Bob za Cyrila. Skutečné
        // vztahy zůstávají dva, i když by se skupina srovnala jedinou platbou.
        val result = debts(
            expense(payer = anna, amount = 500, between = listOf(bob)),
            expense(payer = bob, amount = 500, between = listOf(cyril)),
        )

        assertEquals(500, result.between(bob, anna))
        assertEquals(500, result.between(cyril, bob))
        assertEquals(0, result.between(cyril, anna))
    }

    @Test
    fun `prijem obraci smer`() {
        // Anna převzala vratku 900 za všechny → dluží Bobovi i Cyrilovi jejich podíl.
        val result = debts(income(receiver = anna, amount = 900, between = everyone))

        assertEquals(300, result.between(anna, bob))
        assertEquals(300, result.between(anna, cyril))
    }

    @Test
    fun `zbyle halere se neztrati`() {
        // 100 mezi tři nejde beze zbytku.
        val result = debts(expense(payer = anna, amount = 100, between = everyone))
        val total = result.filter { it.to == anna }.sumOf { it.amount.minor }

        // Annin vlastní podíl zůstává jí, zbytek jí dluží ostatní.
        assertEquals(100 - 34, total, "součet dluhů vůči Anně nesedí: $result")
    }

    @Test
    fun `zadny dluh neni nulovy ani zaporny`() {
        val result = debts(
            expense(payer = anna, amount = 901, between = everyone),
            settlement(from = bob, to = anna, amount = 300),
        )
        assertTrue(result.all { it.amount.isPositive })
        assertTrue(result.all { it.from != it.to })
    }

    @Test
    fun `soucet parovych dluhu clena odpovida jeho saldu`() {
        // Klíčový invariant: párové dluhy a salda jsou dva pohledy na tatáž data.
        // Kdyby se rozešly, uživatel by v detailu člena viděl jiné číslo než v bilanci.
        val random = Random(2026)

        repeat(300) {
            val transactions = buildList {
                repeat(random.nextInt(1, 6)) {
                    val payer = everyone[random.nextInt(everyone.size)]
                    val participants = everyone.filter { random.nextBoolean() }
                        .ifEmpty { everyone }
                    add(
                        expense(
                            payer = payer,
                            amount = random.nextLong(1, 100_000),
                            between = participants,
                        ),
                    )
                }
            }

            val balances = computeBalances(transactions, everyone, CurrencyCode.CZK)
                .associate { it.memberId to it.amount.minor }
            val pairwise = computePairwiseDebts(transactions, CurrencyCode.CZK)

            everyone.forEach { member ->
                val owedToMember = pairwise.filter { it.to == member }.sumOf { it.amount.minor }
                val owedByMember = pairwise.filter { it.from == member }.sumOf { it.amount.minor }

                assertEquals(
                    balances.getValue(member),
                    owedToMember - owedByMember,
                    "saldo a párové dluhy se rozešly pro $member u $transactions",
                )
            }
        }
    }

    @Test
    fun `involving vrati jen dluhy daneho clena`() {
        val result = debts(expense(payer = anna, amount = 900, between = everyone))
            .involving(bob)

        assertEquals(1, result.size)
        assertEquals(bob, result.single().from)
    }

    @Test
    fun `transakce bez kurzu se do ciziho vypoctu zapocitat neda`() {
        val transaction = expense(payer = anna, amount = 900, between = everyone)
        val error = runCatching {
            computePairwiseDebts(listOf(transaction), CurrencyCode.EUR)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException, "čekal jsem chybu kurzu, dostal jsem $error")
    }

    @Test
    fun `vysledek nezavisi na poradi transakci`() {
        val first = expense(payer = anna, amount = 900, between = everyone)
        val second = expense(payer = bob, amount = 600, between = listOf(anna, bob))

        assertEquals(
            computePairwiseDebts(listOf(first, second), CurrencyCode.CZK).toSet(),
            computePairwiseDebts(listOf(second, first), CurrencyCode.CZK).toSet(),
        )
    }

    @Test
    fun `castka je Money a ne holy Long`() {
        val result = debts(expense(payer = anna, amount = 900, between = everyone))
        assertEquals(Money(300), result.first { it.from == bob }.amount)
    }
}
