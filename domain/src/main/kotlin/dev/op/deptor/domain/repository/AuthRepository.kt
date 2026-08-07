package dev.op.deptor.domain.repository

import dev.op.deptor.domain.model.UserId
import kotlinx.coroutines.flow.Flow

/**
 * Přihlášený uživatel.
 *
 * [isAnonymous] rozlišuje lokální režim ze zadání: uživatel může appku používat bez
 * registrace a účet si založit až později, aniž by přišel o data — anonymní účet se
 * povýší na plný přes [linkWithEmail], takže si nechá stejné UID a všechna členství.
 */
data class AuthUser(
    val id: UserId,
    val displayName: String?,
    val email: String?,
    val isAnonymous: Boolean,
)

interface AuthRepository {

    /** Aktuální uživatel, nebo null když nikdo přihlášený není. */
    val currentUser: Flow<AuthUser?>

    fun currentUserOrNull(): AuthUser?

    suspend fun signInAnonymously(): Result<AuthUser>

    suspend fun signInWithEmail(email: String, password: String): Result<AuthUser>

    suspend fun registerWithEmail(email: String, password: String, displayName: String): Result<AuthUser>

    /** Povýší anonymní účet na plný, se zachováním UID a tím i všech dat. */
    suspend fun linkWithEmail(email: String, password: String): Result<AuthUser>

    suspend fun signOut()
}
