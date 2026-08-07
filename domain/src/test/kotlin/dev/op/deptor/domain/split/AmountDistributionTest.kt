package dev.op.deptor.domain.split

import dev.op.deptor.domain.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AmountDistributionTest {

    @Test
    fun `beze zbytku deli rovnomerne`() {
        val result = distribute(Money(900), listOf(1, 1, 1))
        assertEquals(listOf(Money(300), Money(300), Money(300)), result)
    }

    @Test
    fun `zbytek se rozda a nic se neztrati`() {
        // 100,00 Kč mezi tři: 33,34 + 33,33 + 33,33
        val result = distribute(Money(10_000), listOf(1, 1, 1))
        assertEquals(Money(10_000), Money(result.sumOf { it.minor }))
        assertEquals(listOf(Money(3334), Money(3333), Money(3333)), result)
    }

    @Test
    fun `respektuje vahy`() {
        val result = distribute(Money(400), listOf(2, 1, 1))
        assertEquals(listOf(Money(200), Money(100), Money(100)), result)
    }

    @Test
    fun `clen s nulovou vahou nedostane nic`() {
        val result = distribute(Money(300), listOf(1, 0, 1))
        assertEquals(listOf(Money(150), Money(0), Money(150)), result)
    }

    @Test
    fun `nula se rozdeli na same nuly`() {
        val result = distribute(Money.ZERO, listOf(1, 2, 3))
        assertTrue(result.all { it.isZero })
    }

    @Test
    fun `jeden clen dostane vse`() {
        assertEquals(listOf(Money(777)), distribute(Money(777), listOf(1)))
    }

    @Test
    fun `pri shode zbytku rozhoduje poradi`() {
        // 10 mezi 3 stejné váhy: zbytek 1 dostane první index.
        assertEquals(listOf(Money(4), Money(3), Money(3)), distribute(Money(10), listOf(1, 1, 1)))
    }

    @Test
    fun `zadny podil neni zaporny`() {
        val result = distribute(Money(1), listOf(1, 1, 1, 1, 1))
        assertTrue(result.all { !it.isNegative }, "žádný podíl nesmí být záporný: $result")
    }

    @Test
    fun `soucet sedi pro nahodne vstupy`() {
        val random = Random(20260806)
        repeat(5_000) {
            val total = Money(random.nextLong(0, 5_000_000))
            val count = random.nextInt(1, 12)
            val weights = List(count) { random.nextLong(0, 10) }
                .let { if (it.sum() == 0L) it.toMutableList().also { w -> w[0] = 1 } else it }

            val result = distribute(total, weights)

            assertEquals(
                total.minor,
                result.sumOf { it.minor },
                "součet neodpovídá pro total=$total weights=$weights",
            )
            assertEquals(count, result.size)
            assertTrue(result.all { !it.isNegative })
        }
    }

    @Test
    fun `rozdil mezi podily stejne vahy je nejvyse jedna jednotka`() {
        val result = distribute(Money(10_001), List(7) { 1L })
        val min = result.minOf { it.minor }
        val max = result.maxOf { it.minor }
        assertTrue(max - min <= 1, "podíly se liší o víc než haléř: $result")
    }

    @Test
    fun `odmitne prazdny seznam`() {
        assertFailsWith<IllegalArgumentException> { distribute(Money(100), emptyList()) }
    }

    @Test
    fun `odmitne same nulove vahy`() {
        assertFailsWith<IllegalArgumentException> { distribute(Money(100), listOf(0, 0)) }
    }

    @Test
    fun `odmitne zapornou vahu`() {
        assertFailsWith<IllegalArgumentException> { distribute(Money(100), listOf(1, -1)) }
    }

    @Test
    fun `odmitne zapornou castku`() {
        assertFailsWith<IllegalArgumentException> { distribute(Money(-100), listOf(1, 1)) }
    }

    @Test
    fun `hlasi preteceni misto tiche chyby`() {
        assertFailsWith<ArithmeticException> {
            distribute(Money(Long.MAX_VALUE / 2), listOf(1_000_000L, 1))
        }
    }
}
