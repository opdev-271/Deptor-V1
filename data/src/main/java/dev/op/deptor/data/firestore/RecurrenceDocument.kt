package dev.op.deptor.data.firestore

import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceFrequency
import dev.op.deptor.domain.recurrence.RecurrenceId
import dev.op.deptor.domain.recurrence.RecurrenceRule
import java.time.LocalDate

/**
 * Opakující se transakce ve Firestore.
 *
 * Vzor transakce se ukládá jako [TransactionDocument], takže se pro něj použije
 * tentýž mapper i tatáž kontrola invariantu jako u běžných výdajů — vzor s rozbitým
 * součtem by generoval rozbité transakce donekonečna.
 *
 * Data jsou řetězce ve tvaru ISO. Firestore `Timestamp` by nesl i čas a zónu, které
 * u pravidla nedávají smysl: „prvního v měsíci" je datum, ne okamžik.
 */
data class RecurrenceDocument(
    val frequency: String = "",
    val interval: Int = 1,
    val startDate: String = "",
    val endDate: String? = null,
    val generatedThrough: String? = null,
    val active: Boolean = true,
    val template: TransactionDocument? = null,
)

fun RecurrenceDocument.toDomain(id: String, groupId: GroupId): Result<Recurrence> = runCatching {
    val frequency = requireNotNull(
        RecurrenceFrequency.entries.firstOrNull { it.name == frequency },
    ) { "Neznámá frekvence opakování '${this.frequency}'" }

    val template = requireNotNull(template) { "Opakování nemá vzor transakce" }
        .toDomain(id = "vzor", groupId = groupId)
        .getOrThrow()

    Recurrence(
        id = RecurrenceId(id),
        groupId = groupId,
        rule = RecurrenceRule(
            frequency = frequency,
            startDate = LocalDate.parse(startDate),
            interval = interval,
            endDate = endDate?.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
        ),
        template = template,
        generatedThrough = generatedThrough?.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
        active = active,
    )
}

fun Recurrence.toDocument(): RecurrenceDocument = RecurrenceDocument(
    frequency = rule.frequency.name,
    interval = rule.interval,
    startDate = rule.startDate.toString(),
    endDate = rule.endDate?.toString(),
    generatedThrough = generatedThrough?.toString(),
    active = active,
    template = template.toDocument(),
)
