package dev.op.deptor.domain.split

import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Split
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BillSplitterTest {

    private val anna = MemberId("a-anna")
    private val bob = MemberId("b-bob")
    private val cyril = MemberId("c-cyril")

    // MemberId je value class a vararg jí Kotlin nedovolí, proto seznam.
    private fun item(name: String, amount: Long, consumers: List<MemberId>) =
        BillItem(name, Money(amount), consumers)

    private fun List<Split>.forMember(member: MemberId): Long =
        firstOrNull { it.memberId == member }?.amount?.minor ?: 0L

    @Test
    fun `kazdy plati jen to co si dal`() {
        val result = splitByItems(
            listOf(
                item("pivo", 5_000, listOf(anna)),
                item("guláš", 18_000, listOf(bob)),
            ),
        )

        assertEquals(5_000, result.forMember(anna))
        assertEquals(18_000, result.forMember(bob))
    }

    @Test
    fun `sdilena polozka se deli rovnym dilem`() {
        val result = splitByItems(listOf(item("pizza", 30_000, listOf(anna, bob, cyril))))

        assertEquals(10_000, result.forMember(anna))
        assertEquals(10_000, result.forMember(bob))
        assertEquals(10_000, result.forMember(cyril))
    }

    @Test
    fun `nedelitelna sdilena polozka nic neztrati`() {
        val result = splitByItems(listOf(item("pizza", 10_000, listOf(anna, bob, cyril))))
        assertEquals(10_000L, result.sumOf { it.amount.minor })
    }

    @Test
    fun `kdo si nedal nic v rozuctovani neni`() {
        val result = splitByItems(listOf(item("pivo", 5_000, listOf(anna))))

        assertEquals(1, result.size)
        assertEquals(anna, result.single().memberId)
    }

    @Test
    fun `spropitne se rozpocita podle utraty`() {
        // Anna si dala za 300, Bob za 100 → spropitné 40 se dělí 30 / 10.
        // Rovným dílem by ten, kdo si dal polévku, doplácel na cizí víno.
        val result = splitByItems(
            items = listOf(
                item("steak", 30_000, listOf(anna)),
                item("polévka", 10_000, listOf(bob)),
            ),
            tip = Money(4_000),
        )

        assertEquals(33_000, result.forMember(anna))
        assertEquals(11_000, result.forMember(bob))
    }

    @Test
    fun `dan se rozpocita stejnym principem jako spropitne`() {
        val result = splitByItems(
            items = listOf(
                item("jídlo", 20_000, listOf(anna)),
                item("jídlo", 20_000, listOf(bob)),
            ),
            tax = Money(4_000),
        )

        assertEquals(22_000, result.forMember(anna))
        assertEquals(22_000, result.forMember(bob))
    }

    @Test
    fun `dan i spropitne zaroven`() {
        val result = splitByItems(
            items = listOf(item("oběd", 20_000, listOf(anna, bob))),
            tax = Money(2_000),
            tip = Money(1_000),
        )

        assertEquals(23_000L, result.sumOf { it.amount.minor })
        assertEquals(result.forMember(anna), result.forMember(bob))
    }

    @Test
    fun `soucet vzdy sedi na celkovou castku uctu`() {
        val random = Random(2026)
        val members = listOf(anna, bob, cyril)

        repeat(2_000) {
            val items = List(random.nextInt(1, 6)) { index ->
                val consumers = members.filter { random.nextBoolean() }.ifEmpty { members }
                item("položka$index", random.nextLong(1, 50_000), consumers)
            }
            val tax = Money(random.nextLong(0, 5_000))
            val tip = Money(random.nextLong(0, 5_000))

            val result = splitByItems(items, tax, tip)
            val expected = billTotal(items, tax, tip)

            assertEquals(
                expected.minor,
                result.sumOf { it.amount.minor },
                "nesedí pro $items, daň $tax, spropitné $tip",
            )
        }
    }

    @Test
    fun `vysledek nezavisi na poradi polozek`() {
        val first = item("pivo", 5_000, listOf(anna))
        val second = item("guláš", 18_003, listOf(bob, cyril))

        assertEquals(
            splitByItems(listOf(first, second), tip = Money(1_111)).toSet(),
            splitByItems(listOf(second, first), tip = Money(1_111)).toSet(),
        )
    }

    @Test
    fun `vysledek nezavisi na poradi konzumentu v polozce`() {
        // Dvě zařízení mohou mít členy v jiném pořadí a musí dojít ke stejnému
        // rozdělení, jinak by se salda rozešla.
        assertEquals(
            splitByItems(listOf(item("pizza", 10_000, listOf(anna, bob, cyril)))).toSet(),
            splitByItems(listOf(item("pizza", 10_000, listOf(cyril, bob, anna)))).toSet(),
        )
    }

    @Test
    fun `celkova castka je soucet polozek dane a spropitneho`() {
        val items = listOf(item("a", 10_000, listOf(anna)), item("b", 5_000, listOf(bob)))
        assertEquals(Money(17_000), billTotal(items, tax = Money(1_000), tip = Money(1_000)))
    }

    @Test
    fun `prazdny ucet je chyba`() {
        assertFailsWith<IllegalArgumentException> { splitByItems(emptyList()) }
    }

    @Test
    fun `polozka bez konzumenta je chyba`() {
        assertFailsWith<IllegalArgumentException> { BillItem("pivo", Money(5_000), emptyList()) }
    }

    @Test
    fun `duplicitni konzument v polozce je chyba`() {
        assertFailsWith<IllegalArgumentException> {
            BillItem("pivo", Money(5_000), listOf(anna, anna))
        }
    }

    @Test
    fun `zaporna cena polozky je chyba`() {
        assertFailsWith<IllegalArgumentException> { BillItem("pivo", Money(-1), listOf(anna)) }
    }

    @Test
    fun `zaporna dan nebo spropitne je chyba`() {
        val items = listOf(item("a", 1_000, listOf(anna)))
        assertFailsWith<IllegalArgumentException> { splitByItems(items, tax = Money(-1)) }
        assertFailsWith<IllegalArgumentException> { splitByItems(items, tip = Money(-1)) }
    }

    @Test
    fun `spropitne bez utraty se rozpocitat neda`() {
        assertFailsWith<IllegalArgumentException> {
            splitByItems(listOf(item("nic", 0, listOf(anna))), tip = Money(1_000))
        }
    }

    @Test
    fun `zadny podil neni zaporny`() {
        val result = splitByItems(
            items = listOf(item("a", 1, listOf(anna, bob, cyril))),
            tip = Money(1),
        )
        assertTrue(result.all { !it.amount.isNegative })
    }
}
