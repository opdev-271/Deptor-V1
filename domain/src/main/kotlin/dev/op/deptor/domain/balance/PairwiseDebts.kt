package dev.op.deptor.domain.balance

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.money.convertedTo
import dev.op.deptor.domain.split.distribute

/** Kolik [from] dluží člověku [to] — po vzájemném vynetování, takže vždy kladné. */
data class PairwiseDebt(
    val from: MemberId,
    val to: MemberId,
    val amount: Money,
) {
    init {
        require(amount.isPositive) { "Dluh musí být kladný, dostal jsem $amount" }
        require(from != to) { "Dluh sám sobě nedává smysl" }
    }
}

/**
 * Kdo komu kolik skutečně dluží, pár po páru.
 *
 * Je to něco jiného než [simplify]: ten navrhuje, jak se skupina srovná co nejmenším
 * počtem plateb, a klidně pošle peníze někomu, s kým dlužník nikdy nic neměl. Tenhle
 * výpočet naopak drží skutečné vztahy — kdo za koho zaplatil.
 *
 * Podíl každého člena se rozpočítá mezi plátce v poměru toho, kolik kdo vložil: když
 * Anna zaplatila 600 a Bob 300 z účtu za 900, pak Cyrilův podíl 300 dluží ze dvou
 * třetin Anně a z jedné Bobovi. Rozpočet používá stejnou metodu největšího zbytku
 * jako rozdělování výdajů, takže se ani tady neztrácí haléře.
 *
 * Vlastní podíl vlastního vkladu se zahazuje — nikdo nedluží sám sobě.
 *
 * Vzájemné dluhy se nakonec vynetují: když Anna dluží Bobovi 100 a Bob Anně 30,
 * výsledkem je jediný dluh Anna → Bob ve výši 70.
 *
 * Platí, že součet párových dluhů člena odpovídá jeho saldu z [computeBalances] —
 * jde o dva pohledy na tatáž data.
 */
fun computePairwiseDebts(
    transactions: List<Transaction>,
    currency: CurrencyCode,
): List<PairwiseDebt> {
    val owed = HashMap<Pair<MemberId, MemberId>, Long>()

    for (original in transactions) {
        // Stejně jako u sald: cizí měna se nejdřív převede na měnu skupiny.
        val transaction = original.convertedTo(currency)

        // U příjmu jsou role obrácené: kdo peníze převzal, ten je dluží těm,
        // mezi které se příjem rozpočítal.
        val creditors = if (transaction.type == TransactionType.INCOME) {
            transaction.splits.map { it.memberId to it.amount.minor }
        } else {
            transaction.payers.map { it.memberId to it.amount.minor }
        }
        val debtors = if (transaction.type == TransactionType.INCOME) {
            transaction.payers.map { it.memberId to it.amount.minor }
        } else {
            transaction.splits.map { it.memberId to it.amount.minor }
        }

        // Kanonické pořadí věřitelů, aby rozdávání zbylých haléřů dopadlo stejně
        // na každém zařízení.
        val orderedCreditors = creditors.filter { it.second > 0 }.sortedBy { it.first.value }
        if (orderedCreditors.isEmpty()) continue

        for ((debtor, share) in debtors) {
            if (share <= 0) continue

            val allocation = distribute(Money(share), orderedCreditors.map { it.second })

            orderedCreditors.forEachIndexed { index, (creditor, _) ->
                val amount = allocation[index].minor
                if (amount > 0 && creditor != debtor) {
                    owed.merge(debtor to creditor, amount, Long::plus)
                }
            }
        }
    }

    return netOut(owed)
}

/** Dluhy, které se týkají daného člena. */
fun List<PairwiseDebt>.involving(memberId: MemberId): List<PairwiseDebt> =
    filter { it.from == memberId || it.to == memberId }

/**
 * Vzájemné dluhy se vyruší, aby si dva lidé neposílali peníze oběma směry.
 * Výsledek je seřazený, aby se pořadí neměnilo mezi zařízeními.
 */
private fun netOut(owed: Map<Pair<MemberId, MemberId>, Long>): List<PairwiseDebt> {
    val processed = mutableSetOf<Pair<MemberId, MemberId>>()
    val result = mutableListOf<PairwiseDebt>()

    for ((pair, amount) in owed) {
        val (debtor, creditor) = pair
        if (pair in processed) continue
        processed += pair
        processed += creditor to debtor

        val net = amount - (owed[creditor to debtor] ?: 0L)
        when {
            net > 0 -> result += PairwiseDebt(debtor, creditor, Money(net))
            net < 0 -> result += PairwiseDebt(creditor, debtor, Money(-net))
        }
    }

    return result.sortedWith(compareBy({ it.from.value }, { it.to.value }))
}
