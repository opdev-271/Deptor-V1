package dev.op.deptor.domain.recurrence

import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.repository.RecurrenceRepository
import dev.op.deptor.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Dožene transakce, které měly z opakování vzniknout.
 *
 * Generuje se **na zařízeních členů**, ne na serveru — ten by znamenal Cloud Functions
 * a placený tarif. Cenou je, že se pravidlo projeví, až někdo aplikaci otevře; zameškaná
 * období se ale dohání zpětně, takže se nic neztratí, jen se to objeví později.
 *
 * Duplicitám brání deterministické id transakce (viz [Recurrence.transactionId]):
 * i když stejné pravidlo vygenerují dva lidé najednou, druhý zápis jen přepíše ten první.
 *
 * @return počet vytvořených transakcí.
 */
suspend fun generateDueTransactions(
    groupId: GroupId,
    recurrences: RecurrenceRepository,
    transactions: TransactionRepository,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    var created = 0

    recurrences.observeRecurrences(groupId).first().forEach { recurrence ->
        val due = recurrence.dueTransactions(today, zone)
        if (due.isEmpty()) return@forEach

        due.forEach { transaction ->
            transactions.addTransaction(transaction).onSuccess { created++ }
        }

        // Posun se ukládá až po zápisu transakcí. Kdyby se uložil dřív a zápis selhal,
        // termín by se přeskočil a výdaj by nikdy nevznikl.
        recurrences.markGenerated(
            groupId = groupId,
            id = recurrence.id,
            through = due.last().date.atZone(zone).toLocalDate(),
        )
    }

    return created
}
