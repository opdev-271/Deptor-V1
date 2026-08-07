package dev.op.deptor.data.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.op.deptor.data.repository.FirebaseAuthRepository
import dev.op.deptor.data.repository.FirestoreGroupRepository
import dev.op.deptor.data.repository.FirestoreRecurrenceRepository
import dev.op.deptor.data.repository.FrankfurterExchangeRateRepository
import dev.op.deptor.data.repository.FirestoreTransactionRepository
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.ExchangeRateRepository
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.domain.repository.RecurrenceRepository
import dev.op.deptor.domain.repository.TransactionRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    /**
     * Firestore tak, jak ho nastavil [dev.op.deptor.data.FirebaseBootstrap].
     *
     * Offline režim ze zadání se tu **nezapíná ručně** — trvalá lokální cache je ve
     * Firestore na Androidu výchozí. Sahat sem na `firestoreSettings` by naopak škodilo:
     * přiřazení nových settings po `useEmulator` shodí `sslEnabled` zpět na true a SDK
     * pak zkouší TLS proti plaintextovému emulátoru, což skončí desetivteřinovým
     * timeoutem a tichým přepnutím do offline režimu.
     */
    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: FirestoreGroupRepository): GroupRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(impl: FirestoreTransactionRepository): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindRecurrenceRepository(
        impl: FirestoreRecurrenceRepository,
    ): RecurrenceRepository

    @Binds
    @Singleton
    abstract fun bindExchangeRateRepository(
        impl: FrankfurterExchangeRateRepository,
    ): ExchangeRateRepository
}
