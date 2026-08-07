package dev.op.deptor.domain.split

import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Split
import dev.op.deptor.domain.model.sumOfMoney
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExpenseSplitterTest {

    private val anna = MemberId("a-anna")
    private val bob = MemberId("b-bob")
    private val cyril = MemberId("c-cyril")

    @Test
    fun `rovnym dilem`() {
        val splits = ExpenseSplitter.equally(Money(9_000), listOf(anna, bob, cyril))
        assertEquals(Money(9_000), splits.sumOfMoney { it.amount })
        assertEquals(listOf(Money(3_000), Money(3_000), Money(3_000)), splits.map { it.amount })
    }

    @Test
    fun `rovnym dilem vraci podily v poradi vstupu`() {
        val splits = ExpenseSplitter.equally(Money(300), listOf(cyril, anna, bob))
        assertEquals(listOf(cyril, anna, bob), splits.map { it.memberId })
    }

    @Test
    fun `zbyly haler pripadne stejnemu clenovi bez ohledu na poradi vstupu`() {
        // Tohle je to podstatné: dvě zařízení mají členy v jiném pořadí a musí
        // dojít ke stejnému rozdělení, jinak se salda rozejdou.
        val a = ExpenseSplitter.equally(Money(10_000), listOf(anna, bob, cyril))
        val b = ExpenseSplitter.equally(Money(10_000), listOf(cyril, bob, anna))

        val byMemberA = a.associate { it.memberId to it.amount }
        val byMemberB = b.associate { it.memberId to it.amount }
        assertEquals(byMemberA, byMemberB)
    }

    @Test
    fun `podle vah`() {
        val splits = ExpenseSplitter.byWeights(Money(4_000), listOf(anna to 2, bob to 1, cyril to 1))
        assertEquals(Money(2_000), splits.first { it.memberId == anna }.amount)
        assertEquals(Money(1_000), splits.first { it.memberId == bob }.amount)
        assertEquals(Money(4_000), splits.sumOfMoney { it.amount })
    }

    @Test
    fun `clen s nulovou vahou v rozdeleni nefiguruje`() {
        val splits = ExpenseSplitter.byWeights(Money(1_000), listOf(anna to 1, bob to 0, cyril to 1))
        assertEquals(listOf(anna, cyril), splits.map { it.memberId })
    }

    @Test
    fun `podle procent`() {
        val splits = ExpenseSplitter.byPercent(
            Money(10_000),
            listOf(anna to 5_000, bob to 2_500, cyril to 2_500),
        )
        assertEquals(Money(5_000), splits.first { it.memberId == anna }.amount)
        assertEquals(Money(10_000), splits.sumOfMoney { it.amount })
    }

    @Test
    fun `procenta s desetinami nic neztrati`() {
        val splits = ExpenseSplitter.byPercent(
            Money(10_000),
            listOf(anna to 3_333, bob to 3_333, cyril to 3_334),
        )
        assertEquals(Money(10_000), splits.sumOfMoney { it.amount })
    }

    @Test
    fun `procenta musi dat sto`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExpenseSplitter.byPercent(Money(100), listOf(anna to 5_000, bob to 4_000))
        }
        assertEquals(true, ex.message?.contains("100 %"))
    }

    @Test
    fun `presne castky projdou kdyz soucet sedi`() {
        val splits = ExpenseSplitter.exact(Money(1_000), listOf(anna to Money(700), bob to Money(300)))
        assertEquals(listOf(Split(anna, Money(700)), Split(bob, Money(300))), splits)
    }

    @Test
    fun `presne castky se odmitnou kdyz soucet nesedi`() {
        assertFailsWith<IllegalArgumentException> {
            ExpenseSplitter.exact(Money(1_000), listOf(anna to Money(700), bob to Money(200)))
        }
    }

    @Test
    fun `duplicitni clen je chyba`() {
        assertFailsWith<IllegalArgumentException> {
            ExpenseSplitter.equally(Money(100), listOf(anna, anna))
        }
    }

    @Test
    fun `same nulove vahy jsou chyba`() {
        assertFailsWith<IllegalArgumentException> {
            ExpenseSplitter.byWeights(Money(100), listOf(anna to 0, bob to 0))
        }
    }
}
