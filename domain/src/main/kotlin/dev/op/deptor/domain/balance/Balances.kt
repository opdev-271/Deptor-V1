package dev.op.deptor.domain.balance

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.money.convertedTo

/**
 * Čisté saldo jednoho člena: kolik vložil mínus kolik spotřeboval.
 *
 * Kladné = skupina dluží jemu (přeplatek), záporné = on dluží skupině (nedoplatek).
 */
data class Balance(
    val memberId: MemberId,
    val amount: Money,
) {
    val isSettled: Boolean get() = amount.isZero
}

/**
 * Spočítá salda členů z historie transakcí.
 *
 * Salda se nikdy neukládají — jsou vždy odvozená. Uložené saldo by se dřív nebo později
 * rozešlo se skutečností (smazaná transakce, konflikt při synchronizaci) a chyba by
 * byla neviditelná; přepočet z transakcí je vždycky pravda.
 *
 * Součet všech sald je z definice nula, protože každá transakce má součet plátců rovný
 * součtu podílů (invariant vynucený v [Transaction]).
 *
 * @param members členové, kteří mají být ve výsledku i když nemají žádnou transakci
 *   (nový člen se má zobrazit s nulou, ne chybět).
 * @param currency měna skupiny. Transakce zadané v jiné měně se převedou kurzem,
 *   který si nesou z doby zadání.
 */
fun computeBalances(
    transactions: List<Transaction>,
    members: List<MemberId>,
    currency: CurrencyCode,
): List<Balance> {
    val totals = LinkedHashMap<MemberId, Long>()
    members.forEach { totals[it] = 0L }

    for (original in transactions) {
        // Transakce v cizí měně se převede kurzem uloženým u ní. Bez toho by se
        // sčítaly koruny s eury a saldo by nedávalo smysl.
        val transaction = original.convertedTo(currency)

        // Příjem obrací směr: kdo peníze fyzicky převzal, ten je skupině dluží,
        // a "podíly" jsou naopak prospěch jednotlivých členů.
        val sign = if (transaction.type == TransactionType.INCOME) -1L else 1L

        transaction.payers.forEach { payer ->
            totals.merge(payer.memberId, sign * payer.amount.minor, Long::plus)
        }
        transaction.splits.forEach { split ->
            totals.merge(split.memberId, -sign * split.amount.minor, Long::plus)
        }
    }

    return totals.map { (memberId, minor) -> Balance(memberId, Money(minor)) }
}

/** Transakce, které se týkají daného člena — podklad pro detail členu. */
fun List<Transaction>.involving(memberId: MemberId): List<Transaction> =
    filter { memberId in it.involvedMembers }
