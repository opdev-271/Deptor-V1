package dev.op.deptor.domain.repository

import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import kotlinx.coroutines.flow.Flow

/**
 * Dokument, který se nepodařilo přečíst jako platnou transakci.
 *
 * Vadný záznam se **nesmí** jen tiše zahodit: salda se počítají ze všech transakcí,
 * takže chybějící záznam znamená tichou chybu ve výsledku, která sedne na nulu a nikde
 * se neprojeví. Proto se odmítnuté dokumenty vracejí spolu s daty a UI o nich musí
 * dát vědět.
 */
data class RejectedDocument(
    val id: String,
    val reason: String,
)

/** Výsledek načtení transakcí — platné záznamy plus to, co se přečíst nepodařilo. */
data class TransactionLoad(
    val transactions: List<Transaction> = emptyList(),
    val rejected: List<RejectedDocument> = emptyList(),
) {
    val hasRejected: Boolean get() = rejected.isNotEmpty()
}

interface TransactionRepository {

    /** Živý proud transakcí skupiny, seřazený od nejnovější. */
    fun observeTransactions(groupId: GroupId): Flow<TransactionLoad>

    suspend fun addTransaction(transaction: Transaction): Result<TransactionId>

    suspend fun updateTransaction(transaction: Transaction): Result<Unit>

    suspend fun deleteTransaction(groupId: GroupId, id: TransactionId): Result<Unit>
}
