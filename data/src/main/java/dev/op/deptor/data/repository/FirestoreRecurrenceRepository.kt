package dev.op.deptor.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import dev.op.deptor.data.firestore.RecurrenceDocument
import dev.op.deptor.data.firestore.reportFailureAsync
import dev.op.deptor.data.firestore.snapshots
import dev.op.deptor.data.firestore.toDocument
import dev.op.deptor.data.firestore.toDomain
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceId
import dev.op.deptor.domain.repository.RecurrenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRecurrenceRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : RecurrenceRepository {

    private fun collection(groupId: GroupId) =
        firestore.collection(GROUPS).document(groupId.value).collection(RECURRENCES)

    override fun observeRecurrences(groupId: GroupId): Flow<List<Recurrence>> =
        collection(groupId).snapshots().map { snapshot ->
            snapshot.documents.mapNotNull { document ->
                document.toObject(RecurrenceDocument::class.java)
                    ?.toDomain(document.id, groupId)
                    ?.getOrNull()
            }
        }

    override suspend fun addRecurrence(recurrence: Recurrence): Result<RecurrenceId> = runCatching {
        val reference = collection(recurrence.groupId).document()
        reference.set(recurrence.toDocument()).reportFailureAsync("addRecurrence")
        RecurrenceId(reference.id)
    }

    override suspend fun deleteRecurrence(groupId: GroupId, id: RecurrenceId): Result<Unit> =
        runCatching {
            collection(groupId).document(id.value).delete().reportFailureAsync("deleteRecurrence")
        }

    override suspend fun markGenerated(
        groupId: GroupId,
        id: RecurrenceId,
        through: LocalDate,
    ): Result<Unit> = runCatching {
        collection(groupId).document(id.value)
            .update("generatedThrough", through.toString())
            .reportFailureAsync("markGenerated")
    }

    private companion object {
        const val GROUPS = "groups"
        const val RECURRENCES = "recurrences"
    }
}
