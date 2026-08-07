package dev.op.deptor.data.repository

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.userProfileChangeRequest
import dev.op.deptor.domain.model.UserId
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.AuthUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
) : AuthRepository {

    override val currentUser: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toDomain()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override fun currentUserOrNull(): AuthUser? = auth.currentUser?.toDomain()

    override suspend fun signInAnonymously(): Result<AuthUser> = runCatching {
        auth.signInAnonymously().await().requireUser()
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> =
        runCatching { auth.signInWithEmailAndPassword(email, password).await().requireUser() }

    override suspend fun registerWithEmail(
        email: String,
        password: String,
        displayName: String,
    ): Result<AuthUser> = runCatching {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        result.user?.updateProfile(userProfileChangeRequest { this.displayName = displayName })
            ?.await()
        result.requireUser().copy(displayName = displayName)
    }

    /**
     * Povýšení anonymního účtu na plný.
     *
     * Podstatné je, že se UID nemění — uživatel si nechá všechna členství ve skupinách
     * i historii. Kdyby se místo linkování vytvořil nový účet, data z lokálního režimu
     * by zůstala viset na nedostupném anonymním UID.
     */
    override suspend fun linkWithEmail(email: String, password: String): Result<AuthUser> =
        runCatching {
            val user = requireNotNull(auth.currentUser) { "Není koho povyšovat, nikdo není přihlášen" }
            require(user.isAnonymous) { "Povýšit lze jen anonymní účet" }

            user.linkWithCredential(EmailAuthProvider.getCredential(email, password))
                .await()
                .requireUser()
        }

    override suspend fun signOut() = auth.signOut()

    private fun com.google.firebase.auth.AuthResult.requireUser(): AuthUser =
        requireNotNull(user?.toDomain()) { "Přihlášení proběhlo, ale nevrátilo uživatele" }

    private fun FirebaseUser.toDomain(): AuthUser = AuthUser(
        id = UserId(uid),
        displayName = displayName?.takeIf { it.isNotBlank() },
        email = email?.takeIf { it.isNotBlank() },
        isAnonymous = isAnonymous,
    )
}
