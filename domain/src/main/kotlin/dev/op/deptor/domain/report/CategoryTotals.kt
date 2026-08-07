package dev.op.deptor.domain.report

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.money.convertedTo
import dev.op.deptor.domain.split.distribute

/**
 * Kolik padlo na jednu kategorii.
 *
 * @param category `null` u výdajů bez kategorie.
 * @param shareBasisPoints podíl na celkové útratě v setinách procenta (33,33 % = 3333).
 */
data class CategoryTotal(
    val category: ExpenseCategory?,
    val total: Money,
    val shareBasisPoints: Int,
)

/**
 * Rozpad útraty skupiny podle kategorií, od největší.
 *
 * Počítají se **jen výdaje**. Vyrovnání je přesun peněz mezi lidmi, ne útrata, a kdyby
 * se do přehledu započítalo, „utratili jsme" by rostlo pokaždé, když si někdo pošle
 * dluh zpátky. Příjmy se nezapočítávají také — snižovat jimi útratu v kategorii by
 * dávalo smysl jen u vratky za konkrétní nákup, což se z dat nepozná.
 *
 * Podíly se dopočítávají metodou největšího zbytku, takže dají dohromady přesně 100 %
 * a v přehledu se neobjeví součet typu 99,98 %.
 */
fun categoryTotals(
    transactions: List<Transaction>,
    currency: CurrencyCode,
): List<CategoryTotal> {
    val sums = LinkedHashMap<ExpenseCategory?, Long>()

    transactions
        .filter { it.type == TransactionType.EXPENSE }
        .forEach { original ->
            val transaction = original.convertedTo(currency)
            val category = ExpenseCategory.fromId(transaction.categoryId)
            sums.merge(category, transaction.amount.minor, Long::plus)
        }

    if (sums.isEmpty()) return emptyList()

    // Pořadí od největší útraty; při shodě rozhoduje kategorie, aby se seznam
    // nepřeskupoval mezi zařízeními. Nezařazené jde vždy na konec.
    val ordered = sums.entries.sortedWith(
        compareByDescending<Map.Entry<ExpenseCategory?, Long>> { it.value }
            .thenBy { it.key?.ordinal ?: Int.MAX_VALUE },
    )

    val total = ordered.sumOf { it.value }
    val shares = if (total > 0) {
        distribute(Money(FULL_PERCENT.toLong()), ordered.map { it.value })
    } else {
        ordered.map { Money.ZERO }
    }

    return ordered.mapIndexed { index, (category, sum) ->
        CategoryTotal(
            category = category,
            total = Money(sum),
            shareBasisPoints = shares[index].minor.toInt(),
        )
    }
}

private const val FULL_PERCENT = 10_000
