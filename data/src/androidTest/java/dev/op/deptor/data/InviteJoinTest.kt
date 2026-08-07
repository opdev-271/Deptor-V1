package dev.op.deptor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.FirebaseFirestoreException
import dev.op.deptor.data.repository.FirebaseAuthRepository
import dev.op.deptor.data.repository.FirestoreGroupRepository
import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.invite.InviteLink
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Vstup do skupiny přes pozvánku.
 *
 * Tohle je hlavně bezpečnostní test: kód funguje jako oprávnění, takže musí platit,
 * že bez něj se dovnitř nikdo nedostane a s ním nejde udělat nic jiného než přidat
 * sám sebe. Ověřuje se proti skutečným security rules v emulátoru, ne proti fake
 * implementaci — pravidlo, které se nikdy nevyhodnotilo, nic nezaručuje.
 */
@RunWith(AndroidJUnit4::class)
class InviteJoinTest {

    private lateinit var auth: FirebaseAuthRepository
    private lateinit var groups: FirestoreGroupRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        EmulatorAdmin.clearFirestore()
        EmulatorAdmin.clearAuthUsers()
        runCatching { EmulatorFirebase.firestore().enableNetwork().await() }

        auth = FirebaseAuthRepository(EmulatorFirebase.auth())
        groups = FirestoreGroupRepository(EmulatorFirebase.firestore(), auth)
        auth.signOut()
    }

    /** Založí skupinu pod novým uživatelem a vrátí pozvánku do ní. */
    private suspend fun createGroupWithInvite(): Invite {
        auth.signInAnonymously().getOrThrow()
        val groupId = groups.createGroup("Chata", CurrencyCode.CZK, "Anna").getOrThrow()

        val group = withTimeout(30.seconds) {
            groups.observeGroup(groupId).first { it?.inviteCode != null }
        }
        return Invite(groupId, assertNotNull(group?.inviteCode))
    }

    @Test
    fun zalozena_skupina_ma_pozvankovy_kod() = runBlocking<Unit> {
        val invite = createGroupWithInvite()

        assertTrue(
            invite.code.length >= InviteLink.MIN_CODE_LENGTH,
            "kód je příliš krátký na to, aby se nedal uhodnout: ${invite.code}",
        )
    }

    @Test
    fun se_spravnym_kodem_se_da_vstoupit() = runBlocking<Unit> {
        val invite = createGroupWithInvite()

        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        groups.joinGroup(invite, "Bob").getOrThrow()

        // Po vstupu skupinu vidí v seznamu svých skupin.
        val mine = withTimeout(30.seconds) { groups.observeGroups().first { it.isNotEmpty() } }
        assertEquals(listOf("Chata"), mine.map { it.name })
    }

    @Test
    fun po_vstupu_pribude_clenstvi_se_jmenem() = runBlocking<Unit> {
        val invite = createGroupWithInvite()

        auth.signOut()
        auth.signInAnonymously().getOrThrow()
        groups.joinGroup(invite, "Bob").getOrThrow()

        val group = withTimeout(30.seconds) {
            groups.observeGroup(invite.groupId).first { it != null && it.members.size == 2 }
        }
        assertTrue(group!!.members.any { it.name == "Bob" && !it.isVirtual })
    }

    @Test
    fun se_spatnym_kodem_se_vstoupit_neda() = runBlocking<Unit> {
        val invite = createGroupWithInvite()

        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        val result = groups.joinGroup(
            invite.copy(code = InviteLink.generateCode()),
            "Vetřelec",
        )

        assertTrue(result.isFailure, "vstup s cizím kódem musí selhat")
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (result.exceptionOrNull() as? FirebaseFirestoreException)?.code,
        )
    }

    @Test
    fun bez_kodu_se_ke_skupine_nedostane_ani_jeji_id() = runBlocking<Unit> {
        val invite = createGroupWithInvite()

        auth.signOut()
        auth.signInAnonymously().getOrThrow()

        // Znalost id skupiny sama o sobě nestačí — kód je to, co opravňuje.
        val error = runCatching {
            EmulatorFirebase.firestore()
                .collection("groups").document(invite.groupId.value)
                .get().await()
        }.exceptionOrNull()

        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (error as? FirebaseFirestoreException)?.code,
        )
    }

    @Test
    fun pozvanka_neopravnuje_k_prepsani_skupiny() = runBlocking<Unit> {
        val invite = createGroupWithInvite()

        auth.signOut()
        val intruder = auth.signInAnonymously().getOrThrow()

        // Se správným kódem, ale pokusem změnit i název — pravidlo to musí odmítnout,
        // jinak by pozvánka byla vstupenka k přepsání cizí skupiny.
        val error = runCatching {
            EmulatorFirebase.firestore()
                .collection("groups").document(invite.groupId.value)
                .update(
                    mapOf(
                        "memberUids" to com.google.firebase.firestore.FieldValue
                            .arrayUnion(intruder.id.value),
                        "joinToken" to invite.code,
                        "name" to "Ukradeno",
                    ),
                ).await()
        }.exceptionOrNull()

        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (error as? FirebaseFirestoreException)?.code,
        )
    }

    @Test
    fun pozvanka_neopravnuje_k_pridani_nekoho_jineho() = runBlocking<Unit> {
        val invite = createGroupWithInvite()

        auth.signOut()
        val intruder = auth.signInAnonymously().getOrThrow()

        val error = runCatching {
            EmulatorFirebase.firestore()
                .collection("groups").document(invite.groupId.value)
                .update(
                    mapOf(
                        "memberUids" to com.google.firebase.firestore.FieldValue
                            .arrayUnion(intruder.id.value, "uid-nekoho-jineho"),
                        "joinToken" to invite.code,
                    ),
                ).await()
        }.exceptionOrNull()

        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (error as? FirebaseFirestoreException)?.code,
            "přes pozvánku smí přibýt jen ten, kdo ji použil",
        )
    }

    @Test
    fun vstup_do_neexistujici_skupiny_selze() = runBlocking<Unit> {
        auth.signInAnonymously().getOrThrow()

        val result = groups.joinGroup(
            Invite(GroupId("neexistuje"), InviteLink.generateCode()),
            "Bob",
        )
        assertTrue(result.isFailure)
    }
}
