package dev.op.deptor.domain.recurrence

import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import java.time.LocalDate
import java.time.ZoneId
import kotlin.jvm.JvmInline

@JvmInline
value class RecurrenceId(val value: String)

/**
 * Pravidelně se opakující transakce, například měsíční nájem.
 *
 * @param template vzor transakce. Datum se z něj nebere — každý výskyt dostane datum
 *   svého termínu; všechno ostatní (částka, plátci, rozúčtování) se přebírá beze změny.
 * @param generatedThrough poslední termín, pro který už transakce vznikla. `null`
 *   u pravidla, které ještě nikdy negenerovalo.
 */
data class Recurrence(
    val id: RecurrenceId,
    val groupId: GroupId,
    val rule: RecurrenceRule,
    val template: Transaction,
    val generatedThrough: LocalDate? = null,
    val active: Boolean = true,
) {
    /**
     * Transakce, které mají k dnešku vzniknout.
     *
     * Id každé z nich je odvozené z pravidla a termínu, takže je **deterministické**.
     * Na tom stojí celá bezpečnost generování: transakce se zakládají na zařízeních
     * členů, a kdyby dva lidé otevřeli aplikaci ve stejnou chvíli, vygenerovali by
     * nájem dvakrát. Se stejným id druhý zápis jen přepíše ten první.
     */
    fun dueTransactions(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Transaction> {
        if (!active) return emptyList()

        return rule.dueOccurrences(today, generatedThrough).map { date ->
            template.copy(
                id = transactionId(date),
                groupId = groupId,
                // Poledne, ne půlnoc: u půlnoci by posun časové zóny mohl transakci
                // přehodit na předchozí den a v seznamu by seděla u špatného data.
                date = date.atTime(12, 0).atZone(zone).toInstant(),
            )
        }
    }

    fun transactionId(date: LocalDate): TransactionId =
        TransactionId("$RECURRING_PREFIX${id.value}_$date")

    companion object {
        /** Prefix v id, aby šlo poznat, že transakce vznikla z opakování. */
        const val RECURRING_PREFIX = "rec_"

        fun isGenerated(id: TransactionId): Boolean = id.value.startsWith(RECURRING_PREFIX)
    }
}
