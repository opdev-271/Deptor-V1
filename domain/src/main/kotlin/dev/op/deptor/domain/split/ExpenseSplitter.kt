package dev.op.deptor.domain.split

import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Split
import dev.op.deptor.domain.model.sumOfMoney

/**
 * Převádí zadání uživatele na konkrétní částky pro jednotlivé členy.
 *
 * Všechny metody garantují, že součet vrácených podílů je přesně rozdělovaná částka.
 * Pořadí ve výstupu odpovídá pořadí na vstupu, ale rozdělování zbylých haléřů probíhá
 * v pořadí podle MemberId — jinak by dvě zařízení s jinak seřazeným seznamem členů
 * došla k jinému výsledku a saldo by se rozešlo.
 */
object ExpenseSplitter {

    /** Rovným dílem mezi zadané členy. */
    fun equally(total: Money, members: List<MemberId>): List<Split> =
        byWeights(total, members.map { it to 1 })

    /**
     * Podle vah/podílů, např. 2:1:1. Člen s váhou 0 se neúčastní; pokud by tím nezbyl
     * nikdo s kladnou váhou, je to chyba zadání.
     */
    fun byWeights(total: Money, weights: List<Pair<MemberId, Int>>): List<Split> {
        require(weights.isNotEmpty()) { "Výdaj musí být rozdělen alespoň mezi jednoho člena" }
        requireNoDuplicates(weights.map { it.first })

        val participants = weights.filter { it.second > 0 }
        require(participants.isNotEmpty()) { "Alespoň jeden člen musí mít kladnou váhu" }

        return allocate(total, participants.map { it.first }, participants.map { it.second.toLong() })
    }

    /**
     * Podle procent. Procenta se zadávají v setinách procenta (basis points), aby se
     * nikde nepracovalo s desetinnými čísly: 33,33 % = 3333. Součet musí být 100 %.
     */
    fun byPercent(total: Money, percentBasisPoints: List<Pair<MemberId, Int>>): List<Split> {
        require(percentBasisPoints.isNotEmpty()) { "Výdaj musí být rozdělen alespoň mezi jednoho člena" }
        requireNoDuplicates(percentBasisPoints.map { it.first })

        val sum = percentBasisPoints.sumOf { it.second }
        require(sum == FULL_PERCENT) {
            "Procenta musí dát dohromady 100 %, dostal jsem ${sum / 100.0} %"
        }
        require(percentBasisPoints.all { it.second >= 0 }) { "Procento nesmí být záporné" }

        val participants = percentBasisPoints.filter { it.second > 0 }
        return allocate(total, participants.map { it.first }, participants.map { it.second.toLong() })
    }

    /**
     * Přesné částky zadané ručně. Nic se nepočítá, jen se ověří, že součet sedí —
     * uživatel musí rozdělit celou částku, jinak by transakce porušila invariant
     * a salda by nevyšla.
     */
    fun exact(total: Money, amounts: List<Pair<MemberId, Money>>): List<Split> {
        require(amounts.isNotEmpty()) { "Výdaj musí být rozdělen alespoň mezi jednoho člena" }
        requireNoDuplicates(amounts.map { it.first })
        require(amounts.all { !it.second.isNegative }) { "Podíl nesmí být záporný" }

        val sum = amounts.sumOfMoney { it.second }
        require(sum == total) {
            "Součet zadaných částek ($sum) se musí rovnat částce výdaje ($total)"
        }
        return amounts.map { (member, amount) -> Split(member, amount) }
    }

    /**
     * Rozdělí částku podle vah a vrátí ji v pořadí odpovídajícím [members].
     * Samotné rozdávání zbytků probíhá nad seznamem seřazeným podle MemberId.
     */
    private fun allocate(
        total: Money,
        members: List<MemberId>,
        weights: List<Long>,
    ): List<Split> {
        val canonicalOrder = members.indices.sortedBy { members[it].value }
        val distributed = distribute(total, canonicalOrder.map { weights[it] })

        val byMember = HashMap<MemberId, Money>(members.size)
        canonicalOrder.forEachIndexed { position, originalIndex ->
            byMember[members[originalIndex]] = distributed[position]
        }
        return members.map { Split(it, byMember.getValue(it)) }
    }

    private fun requireNoDuplicates(members: List<MemberId>) {
        require(members.toSet().size == members.size) {
            "Stejný člen je v rozdělení uveden vícekrát"
        }
    }

    private const val FULL_PERCENT = 10_000
}
