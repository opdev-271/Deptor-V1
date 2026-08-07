package dev.op.deptor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.FirebaseFirestoreException
import dev.op.deptor.data.repository.FirebaseAuthRepository
import dev.op.deptor.data.repository.FirestoreGroupRepository
import dev.op.deptor.domain.model.CurrencyCode
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Testy security rules.
 *
 * Emulátor načítá `firebase/firestore.rules` z `firebase.json` a vynucuje je i vůči
 * Android klientovi, takže se dají ověřit stejnou cestou jako repozitáře — bez
 * samostatné JS sady testů.
 *
 * Pozor na jednu past: rules nekontrolují invariant součtů (jazyk rules neumí sečíst
 * pole libovolné délky), takže tady se testuje **jen** přístup a strukturální validace.
 * Invariant hlídá mapper, viz `MappersTest` a test odmítnutého dokumentu.
 */
@RunWith(AndroidJUnit4::class)
class SecurityRulesTest {

    private lateinit var auth: FirebaseAuthRepository
    private lateinit var groups: FirestoreGroupRepository

    @Before
    fun setUp() = runTest {
        EmulatorAdmin.clearFirestore()
        EmulatorAdmin.clearAuthUsers()

        auth = FirebaseAuthRepository(EmulatorFirebase.auth())
        groups = FirestoreGroupRepository(EmulatorFirebase.firestore(), auth)
        auth.signOut()
    }

    @Test
    fun neprihlaseny_uzivatel_se_ke_skupine_nedostane() = runTest(timeout = 60.seconds) {
        auth.signInAnonymously().getOrThrow()
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        auth.signOut()

        val error = runCatching {
            EmulatorFirebase.firestore().collection("groups").document(groupId.value).get().await()
        }.exceptionOrNull()

        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (error as? FirebaseFirestoreException)?.code,
            "nepřihlášený uživatel musí dostat PERMISSION_DENIED, dostal: $error",
        )
    }

    @Test
    fun cizi_uzivatel_neprecte_skupinu_ktere_neni_clenem() = runTest(timeout = 60.seconds) {
        auth.signInAnonymously().getOrThrow()
        val groupId = groups.createGroup("Soukromá", CurrencyCode.CZK, "Anna").getOrThrow()

        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        val error = runCatching {
            EmulatorFirebase.firestore().collection("groups").document(groupId.value).get().await()
        }.exceptionOrNull()

        assertTrue(
            (error as? FirebaseFirestoreException)?.code ==
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
            "cizí skupina musí být nedostupná, dostal jsem: $error",
        )
    }

    @Test
    fun cizi_uzivatel_nezapise_transakci_do_cizi_skupiny() = runTest(timeout = 60.seconds) {
        auth.signInAnonymously().getOrThrow()
        val groupId = groups.createGroup("Soukromá", CurrencyCode.CZK, "Anna").getOrThrow()

        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        val error = runCatching {
            EmulatorFirebase.firestore()
                .collection("groups").document(groupId.value)
                .collection("transactions").document("podvrzena")
                .set(validTransactionMap())
                .await()
        }.exceptionOrNull()

        assertTrue(
            (error as? FirebaseFirestoreException)?.code ==
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
            "zápis do cizí skupiny musí být odmítnut, dostal jsem: $error",
        )
    }

    @Test
    fun skupina_kde_zakladatel_neni_clenem_nevznikne() = runTest(timeout = 60.seconds) {
        val user = auth.signInAnonymously().getOrThrow()

        // Bez sebe v memberUids by vznikla skupina, do které se nikdo nedostane.
        val error = runCatching {
            EmulatorFirebase.firestore().collection("groups").document("sirotek")
                .set(
                    mapOf(
                        "name" to "Sirotek",
                        "currency" to "CZK",
                        "ownerUid" to user.id.value,
                        "memberUids" to emptyList<String>(),
                        "archived" to false,
                    ),
                ).await()
        }.exceptionOrNull()

        assertTrue(
            (error as? FirebaseFirestoreException)?.code ==
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
            "skupina bez zakladatele v memberUids musí být odmítnuta, dostal jsem: $error",
        )
    }

    @Test
    fun transakce_se_zapornou_castkou_neprojde() = runTest(timeout = 60.seconds) {
        auth.signInAnonymously().getOrThrow()
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        val error = runCatching {
            EmulatorFirebase.firestore()
                .collection("groups").document(groupId.value)
                .collection("transactions").document("zaporna")
                .set(validTransactionMap(amount = -100L))
                .await()
        }.exceptionOrNull()

        assertTrue(
            (error as? FirebaseFirestoreException)?.code ==
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
            "záporná částka musí být odmítnuta už v rules, dostal jsem: $error",
        )
    }

    @Test
    fun transakce_s_neznamym_typem_neprojde() = runTest(timeout = 60.seconds) {
        auth.signInAnonymously().getOrThrow()
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        val error = runCatching {
            EmulatorFirebase.firestore()
                .collection("groups").document(groupId.value)
                .collection("transactions").document("divna")
                .set(validTransactionMap(type = "NEJAKY_NESMYSL"))
                .await()
        }.exceptionOrNull()

        assertTrue(
            (error as? FirebaseFirestoreException)?.code ==
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
            "neznámý typ musí být odmítnut, dostal jsem: $error",
        )
    }

    @Test
    fun clen_smi_do_sve_skupiny_zapsat() = runTest(timeout = 60.seconds) {
        auth.signInAnonymously().getOrThrow()
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        // Protipól k testům výše: pravidla nesmí být tak přísná, že zablokují i členy.
        EmulatorFirebase.firestore()
            .collection("groups").document(groupId.value)
            .collection("transactions").document("moje")
            .set(validTransactionMap())
            .await()
    }

    private fun validTransactionMap(
        amount: Long = 900L,
        type: String = "EXPENSE",
    ): Map<String, Any?> = mapOf(
        "type" to type,
        "amountMinor" to amount,
        "currency" to "CZK",
        "date" to com.google.firebase.Timestamp.now(),
        "description" to "výdaj",
        "payers" to listOf(mapOf("memberId" to "anna", "amountMinor" to amount)),
        "splits" to listOf(mapOf("memberId" to "anna", "amountMinor" to amount)),
        "splitMethod" to "EQUAL",
    )
}
