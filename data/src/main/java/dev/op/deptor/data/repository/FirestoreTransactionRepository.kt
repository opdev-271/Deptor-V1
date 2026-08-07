package dev.op.deptor.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dev.op.deptor.data.firestore.TransactionDocument
import dev.op.deptor.data.firestore.reportFailureAsync
import dev.op.deptor.data.firestore.snapshots
import dev.op.deptor.data.firestore.toDocument
import dev.op.deptor.data.firestore.toDomain
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.repository.RejectedDocument
import dev.op.deptor.domain.repository.TransactionLoad
import dev.op.deptor.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreTransactionRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : TransactionRepository {

    private fun collection(groupId: GroupId) =
        firestore.collection(GROUPS).document(groupId.value).collection(TRANSACTIONS)

    override fun observeTransactions(groupId: GroupId): Flow<TransactionLoad> =
        collection(groupId)
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                val transactions = mutableListOf<Transaction>()
                val rejected = mutableListOf<RejectedDocument>()

                snapshot.documents.forEach { document ->
                    val parsed = runCatching { document.toObject(TransactionDocument::class.java) }
                        .mapCatching { requireNotNull(it) { "Prázdný dokument" } }
                        .mapCatching { it.toDomain(document.id, groupId).getOrThrow() }

                    parsed.fold(
                        onSuccess = transactions::add,
                        onFailure = { error ->
                            // Vadný záznam se zahodit nesmí bez upozornění — chyběl by
                            // ve výpočtu sald a výsledek by tiše nesouhlasil.
                            rejected += RejectedDocument(
                                id = document.id,
                                reason = error.message ?: error::class.simpleName.orEmpty(),
                            )
                        },
                    )
                }

                TransactionLoad(transactions, rejected)
            }

    // Zápisy se nečekají na potvrzení serverem — viz reportFailureAsync. Bez toho by
    // přidání výdaje bez signálu zamrzlo, přestože data jsou okamžitě v lokální cache.
    /**
     * Uloží transakci.
     *
     * Když má transakce **vyplněné id, použije se** místo nově vygenerovaného. Na tom
     * stojí opakující se transakce: jejich id je odvozené z pravidla a termínu, takže
     * když stejný nájem vygenerují dvě zařízení naráz, druhý zápis jen přepíše ten
     * první místo aby vznikl duplicitní výdaj.
     */
    override suspend fun addTransaction(transaction: Transaction): Result<TransactionId> =
        runCatching {
            // Firestore přiděluje id klientsky, takže ho máme i bez spojení se serverem.
            val document = transaction.id.value
                .takeIf { it.isNotBlank() }
                ?.let { collection(transaction.groupId).document(it) }
                ?: collection(transaction.groupId).document()

            document.set(transaction.toDocument()).reportFailureAsync("addTransaction")
            TransactionId(document.id)
        }

    override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = runCatching {
        collection(transaction.groupId)
            .document(transaction.id.value)
            .set(transaction.toDocument())
            .reportFailureAsync("updateTransaction")
    }

    override suspend fun deleteTransaction(groupId: GroupId, id: TransactionId): Result<Unit> =
        runCatching {
            collection(groupId).document(id.value).delete().reportFailureAsync("deleteTransaction")
        }

    private companion object {
        const val GROUPS = "groups"
        const val TRANSACTIONS = "transactions"
    }
}
