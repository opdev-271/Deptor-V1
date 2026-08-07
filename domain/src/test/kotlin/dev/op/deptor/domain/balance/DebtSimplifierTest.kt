package dev.op.deptor.domain.balance

import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DebtSimplifierTest {

    private fun balances(vararg pairs: Pair<String, Long>): List<Balance> =
        pairs.map { (name, amount) -> Balance(MemberId(name), Money(amount)) }

    @Test
    fun `vyrovnana skupina nepotrebuje zadne platby`() {
        assertEquals(emptyList(), simplify(balances("a" to 0, "b" to 0)))
    }

    @Test
    fun `jeden dluh jedna platba`() {
        val transfers = simplify(balances("a" to 300, "b" to -300))
        assertEquals(
            listOf(Transfer(from = MemberId("b"), to = MemberId("a"), amount = Money(300))),
            transfers,
        )
    }

    @Test
    fun `dva dluznici jednomu veriteli`() {
        val transfers = simplify(balances("a" to 600, "b" to -300, "c" to -300))
        assertEquals(2, transfers.size)
        assertTrue(transfers.all { it.to == MemberId("a") })
        assertEquals(600L, transfers.sumOf { it.amount.minor })
    }

    @Test
    fun `retez dluhu se zkrati na jedinou platbu`() {
        // A dluží B, B dluží C stejnou částku → stačí jedna platba A→C.
        val transfers = simplify(balances("a" to -500, "b" to 0, "c" to 500))
        assertEquals(
            listOf(Transfer(from = MemberId("a"), to = MemberId("c"), amount = Money(500))),
            transfers,
        )
    }

    @Test
    fun `pocet plateb je nejvyse n minus jedna`() {
        val random = Random(42)
        repeat(500) {
            val size = random.nextInt(2, 15)
            val amounts = MutableList(size - 1) { random.nextLong(-100_000, 100_000) }
            amounts += -amounts.sum()
            val input = amounts.mapIndexed { i, amount -> Balance(MemberId("m$i"), Money(amount)) }

            val transfers = simplify(input)
            val nonZero = input.count { !it.isSettled }

            assertTrue(
                transfers.size <= maxOf(nonZero - 1, 0),
                "příliš mnoho plateb: ${transfers.size} pro $nonZero nenulových sald",
            )
        }
    }

    @Test
    fun `po aplikaci plateb jsou vsechna salda nulova`() {
        val random = Random(2026)
        repeat(2_000) {
            val size = random.nextInt(2, 20)
            val amounts = MutableList(size - 1) { random.nextLong(-1_000_000, 1_000_000) }
            amounts += -amounts.sum()
            val input = amounts.mapIndexed { i, amount -> Balance(MemberId("m$i"), Money(amount)) }

            val settled = input.afterApplying(simplify(input))

            assertTrue(
                settled.all { it.isSettled },
                "po vyrovnání zbyla nenulová salda: ${settled.filterNot { it.isSettled }}",
            )
        }
    }

    @Test
    fun `zadna navrzena platba neni nulova ani zaporna`() {
        val transfers = simplify(balances("a" to 1, "b" to 1, "c" to -2, "d" to 0))
        assertTrue(transfers.all { it.amount.isPositive })
    }

    @Test
    fun `nikdo neplati sam sobe`() {
        val transfers = simplify(balances("a" to 500, "b" to -500))
        assertTrue(transfers.all { it.from != it.to })
    }

    @Test
    fun `vysledek nezavisi na poradi vstupu`() {
        // Dvě zařízení mají členy v jiném pořadí a musí navrhnout totéž,
        // jinak by stejný dluh někdo zaplatil dvakrát.
        val input = balances("a" to 300, "b" to 300, "c" to -300, "d" to -300)

        val fromOneDevice = simplify(input)
        val fromAnother = simplify(input.reversed())

        assertEquals(fromOneDevice.toSet(), fromAnother.toSet())
    }

    @Test
    fun `nenulovy soucet sald je chyba`() {
        assertFailsWith<IllegalArgumentException> {
            simplify(balances("a" to 300, "b" to -200))
        }
    }
}
