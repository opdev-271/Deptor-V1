package dev.op.deptor.domain.balance

import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import java.util.PriorityQueue

/** Navrhovaná platba: [from] má poslat [amount] člověku [to]. */
data class Transfer(
    val from: MemberId,
    val to: MemberId,
    val amount: Money,
) {
    init {
        require(amount.isPositive) { "Převod musí být na kladnou částku, dostal jsem $amount" }
        require(from != to) { "Převod sám sobě nedává smysl" }
    }
}

/**
 * Zjednodušení dluhů — spočítá minimální sadu plateb, kterými se skupina vyrovná.
 *
 * Místo aby si každý platil s každým, hladový algoritmus spáruje vždy největšího
 * dlužníka s největším věřitelem a převede mezi nimi nejvyšší možnou částku. Tím
 * v každém kroku aspoň jednomu členovi vynuluje saldo, takže výsledek má nejvýš
 * `n − 1` plateb místo až `n × (n − 1) / 2`.
 *
 * (Hledání skutečně nejmenšího možného počtu plateb je NP-těžké; tenhle hladový
 * postup je standardní praktické řešení a na velikostech reálných skupin dává
 * ve valné většině případů optimum.)
 *
 * Při shodě částek rozhoduje MemberId, aby dvě zařízení navrhla identické platby —
 * jinak by dva lidé viděli různé "kdo komu má poslat" a poslali to dvakrát.
 *
 * @throws IllegalArgumentException pokud součet sald není nula; to by znamenalo
 *   porušený invariant transakcí a jakýkoli návrh plateb by byl nesmysl.
 */
fun simplify(balances: List<Balance>): List<Transfer> {
    val total = balances.sumOf { it.amount.minor }
    require(total == 0L) {
        "Součet sald musí být nula, je $total — v datech skupiny je nekonzistence"
    }

    // Největší částka první; při shodě nižší MemberId, aby byl výsledek deterministický.
    val byAmountDesc = compareByDescending<Pair<MemberId, Long>> { it.second }
        .thenBy { it.first.value }

    val creditors = PriorityQueue(byAmountDesc)
    val debtors = PriorityQueue(byAmountDesc)

    balances.forEach { balance ->
        when {
            balance.amount.isPositive -> creditors += balance.memberId to balance.amount.minor
            balance.amount.isNegative -> debtors += balance.memberId to -balance.amount.minor
            else -> Unit
        }
    }

    val transfers = mutableListOf<Transfer>()
    while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
        val (creditor, credit) = creditors.poll()
        val (debtor, debt) = debtors.poll()

        val amount = minOf(credit, debt)
        transfers += Transfer(from = debtor, to = creditor, amount = Money(amount))

        if (credit > amount) creditors += creditor to (credit - amount)
        if (debt > amount) debtors += debtor to (debt - amount)
    }

    return transfers
}

/**
 * Aplikuje převody na salda. Slouží k ověření, že navržené platby skutečně skupinu
 * vyrovnají — používá se v testech a hodí se i pro náhled "jak to bude vypadat potom".
 */
fun List<Balance>.afterApplying(transfers: List<Transfer>): List<Balance> {
    val totals = associate { it.memberId to it.amount.minor }.toMutableMap()
    transfers.forEach { transfer ->
        totals.merge(transfer.from, transfer.amount.minor, Long::plus)
        totals.merge(transfer.to, -transfer.amount.minor, Long::plus)
    }
    return map { Balance(it.memberId, Money(totals.getValue(it.memberId))) }
}
