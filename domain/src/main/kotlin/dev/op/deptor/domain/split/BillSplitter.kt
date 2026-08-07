package dev.op.deptor.domain.split

import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Split

/**
 * Jedna položka účtu a kdo si ji dal.
 *
 * @param consumers členové, kteří položku konzumovali. Cena se mezi ně dělí rovným
 *   dílem — sdílená pizza se počítá půl na půl, ne podle toho, kdo snědl víc.
 */
data class BillItem(
    val name: String,
    val amount: Money,
    val consumers: List<MemberId>,
) {
    init {
        require(!amount.isNegative) { "Položka nesmí mít zápornou cenu, dostal jsem $amount" }
        require(consumers.isNotEmpty()) { "U položky '$name' není nikdo, kdo si ji dal" }
        require(consumers.toSet().size == consumers.size) {
            "Položka '$name' má stejného člena uvedeného vícekrát"
        }
    }
}

/**
 * Rozúčtování účtu po položkách.
 *
 * Každý platí jen to, co si dal. Daň a spropitné se pak rozpočítají **poměrně podle
 * útraty** — kdo si dal dražší jídlo, zaplatí i větší díl spropitného. Rozdělit je
 * rovným dílem by znamenalo, že ten, kdo si dal jen polévku, doplácí na cizí víno.
 *
 * Celková částka výdaje je součet položek plus daň plus spropitné; volající ji nemá
 * zadávat ručně, jinak by se dvě čísla mohla rozejít.
 *
 * Členové, kteří si nedali nic, ve výsledku nejsou — nemají co platit, ani z daně
 * a spropitného.
 *
 * Vše se rozděluje metodou největšího zbytku, takže součet podílů přesně odpovídá
 * celkové částce a neztrácí se haléře.
 *
 * @throws IllegalArgumentException když je účet prázdný nebo když by se daň a spropitné
 *   neměly podle čeho rozpočítat (nikdo nic nekonzumoval).
 */
fun splitByItems(
    items: List<BillItem>,
    tax: Money = Money.ZERO,
    tip: Money = Money.ZERO,
): List<Split> {
    require(items.isNotEmpty()) { "Účet neobsahuje žádnou položku" }
    require(!tax.isNegative) { "Daň nesmí být záporná, dostal jsem $tax" }
    require(!tip.isNegative) { "Spropitné nesmí být záporné, dostal jsem $tip" }

    // Kanonické pořadí členů, aby rozdávání zbylých haléřů dopadlo stejně na každém
    // zařízení bez ohledu na to, v jakém pořadí uživatel položky zadával.
    val members = items.flatMap { it.consumers }.distinct().sortedBy { it.value }
    val subtotals = LinkedHashMap<MemberId, Long>().apply { members.forEach { put(it, 0L) } }

    for (item in items) {
        val consumers = item.consumers.sortedBy { it.value }
        val shares = distribute(item.amount, List(consumers.size) { 1L })
        consumers.forEachIndexed { index, member ->
            subtotals.merge(member, shares[index].minor, Long::plus)
        }
    }

    val consumed = subtotals.values.sum()
    val extra = tax + tip
    require(consumed > 0 || extra.isZero) {
        "Daň a spropitné nejde rozpočítat, když si nikdo nic nedal"
    }

    val weights = subtotals.values.toList()
    val taxShares = if (consumed > 0) distribute(tax, weights) else weights.map { Money.ZERO }
    val tipShares = if (consumed > 0) distribute(tip, weights) else weights.map { Money.ZERO }

    return subtotals.entries
        .mapIndexed { index, (member, subtotal) ->
            Split(member, Money(subtotal + taxShares[index].minor + tipShares[index].minor))
        }
        // Kdo si nedal nic, v rozúčtování nefiguruje. Jediná výjimka je celý účet
        // za nulu — pak zůstane jeden nulový podíl, aby seznam nebyl prázdný;
        // takovou transakci stejně neuloží invariant kladné částky.
        .filterNot { it.amount.isZero }
        .ifEmpty { listOf(Split(members.first(), Money.ZERO)) }
}

/** Celková částka účtu — položky plus daň plus spropitné. */
fun billTotal(items: List<BillItem>, tax: Money = Money.ZERO, tip: Money = Money.ZERO): Money =
    Money(items.sumOf { it.amount.minor } + tax.minor + tip.minor)
