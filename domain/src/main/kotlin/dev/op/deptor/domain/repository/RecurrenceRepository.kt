package dev.op.deptor.domain.repository

import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceId
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface RecurrenceRepository {

    fun observeRecurrences(groupId: GroupId): Flow<List<Recurrence>>

    suspend fun addRecurrence(recurrence: Recurrence): Result<RecurrenceId>

    /**
     * Zruší opakování. Už vygenerované transakce zůstávají — jsou to skutečné výdaje,
     * které si někdo zaplatil, a smazat je při zrušení pravidla by rozhodilo salda.
     */
    suspend fun deleteRecurrence(groupId: GroupId, id: RecurrenceId): Result<Unit>

    suspend fun markGenerated(
        groupId: GroupId,
        id: RecurrenceId,
        through: LocalDate,
    ): Result<Unit>
}
