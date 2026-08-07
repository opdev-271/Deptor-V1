package dev.op.deptor.domain.split

import dev.op.deptor.domain.model.Money

/**
 * Rozdělí [total] v poměru [weights] tak, aby součet výsledku byl **přesně** [total].
 *
 * Metoda největšího zbytku: každý dostane celočíselný podíl a haléře, které při dělení
 * zbydou, se rozdají po jedné jednotce těm s největším zbytkem. Naivní zaokrouhlování
 * by 100 Kč mezi tři lidi rozdělilo na 3× 33,33 Kč a jeden haléř by se ztratil —
 * saldo skupiny by pak nikdy nevyšlo na nulu.
 *
 * Při shodě zbytků rozhoduje pořadí v [weights]. Volající proto musí předat stabilní
 * pořadí, jinak dvě zařízení spočítají různý výsledek; veřejné API v [ExpenseSplitter]
 * to zajišťuje řazením podle MemberId.
 *
 * @throws IllegalArgumentException je-li [total] záporná, [weights] prázdné,
 *   obsahuje zápornou váhu, nebo je součet vah nula.
 */
fun distribute(total: Money, weights: List<Long>): List<Money> {
    require(weights.isNotEmpty()) { "Nelze rozdělit mezi nikoho" }
    require(!total.isNegative) { "Rozdělovaná částka nesmí být záporná, dostal jsem $total" }
    require(weights.all { it >= 0 }) { "Váhy nesmí být záporné, dostal jsem $weights" }

    val totalWeight = weights.sumOf { it }
    require(totalWeight > 0) { "Součet vah musí být kladný, dostal jsem $weights" }

    // Math.multiplyExact: raději hlasitá výjimka než tiché přetečení na zápornou částku.
    val base = LongArray(weights.size)
    val remainder = LongArray(weights.size)
    for (i in weights.indices) {
        val numerator = Math.multiplyExact(total.minor, weights[i])
        base[i] = numerator / totalWeight
        remainder[i] = numerator % totalWeight
    }

    // Každý zbytek je menší než totalWeight, takže leftover je vždy < weights.size
    // a na každý index připadne nejvýš jedna jednotka navíc.
    val leftover = (total.minor - base.sum()).toInt()

    // Indexy setříděné podle zbytku sestupně; při shodě vyhrává nižší index.
    weights.indices
        .sortedWith(compareByDescending<Int> { remainder[it] }.thenBy { it })
        .take(leftover)
        .forEach { base[it]++ }

    return base.map { Money(it) }
}
