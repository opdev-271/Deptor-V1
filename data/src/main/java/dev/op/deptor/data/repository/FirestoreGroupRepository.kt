package dev.op.deptor.data.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import dev.op.deptor.data.firestore.GroupDocument
import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.invite.InviteLink
import dev.op.deptor.data.firestore.MemberDocument
import dev.op.deptor.data.firestore.reportFailureAsync
import dev.op.deptor.data.firestore.snapshots
import dev.op.deptor.data.firestore.snapshotsOrNull
import dev.op.deptor.data.firestore.toDocument
import dev.op.deptor.data.firestore.toDomain
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreGroupRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: AuthRepository,
) : GroupRepository {

    private fun groups() = firestore.collection(GROUPS)

    private fun members(groupId: GroupId) =
        groups().document(groupId.value).collection(MEMBERS)

    /**
     * Skupiny přihlášeného uživatele.
     *
     * Dotaz se odvíjí od aktuálního uživatele, takže po odhlášení nebo přepnutí účtu
     * `flatMapLatest` starý listener zruší — jinak by zůstal viset dotaz na cizí data
     * a Firestore by ho odmítl s PERMISSION_DENIED.
     */
    override fun observeGroups(): Flow<List<Group>> =
        auth.currentUser.flatMapLatest { user ->
            if (user == null) {
                flowOf(emptyList())
            } else {
                groups()
                    .whereArrayContains("memberUids", user.id.value)
                    .snapshots()
                    .flatMapLatest { snapshot ->
                        if (snapshot.isEmpty) {
                            flowOf(emptyList())
                        } else {
                            // Členové jsou v podkolekci, takže skupina se skládá ze dvou
                            // zdrojů; combine drží obojí živé.
                            combine(
                                snapshot.documents.map { document ->
                                    observeGroup(GroupId(document.id))
                                },
                            ) { loaded -> loaded.filterNotNull().toList() }
                        }
                    }
            }
        }

    override fun observeGroup(id: GroupId): Flow<Group?> =
        combine(
            groups().document(id.value).snapshotsOrNull(),
            members(id).snapshots(),
        ) { groupSnapshot, memberSnapshot ->
            val document = groupSnapshot?.toObject(GroupDocument::class.java) ?: return@combine null

            val members = memberSnapshot.documents.mapNotNull { memberDocument ->
                memberDocument.toObject(MemberDocument::class.java)
                    ?.toDomain(memberDocument.id)
                    ?.getOrNull()
            }

            document.toDomain(id.value, members).getOrNull()
        }

    override suspend fun createGroup(
        name: String,
        currency: CurrencyCode,
        creatorName: String,
    ): Result<GroupId> = runCatching {
        val user = requireNotNull(auth.currentUserOrNull()) {
            "Skupinu může založit jen přihlášený uživatel"
        }

        val document = groups().document()
        val inviteCode = InviteLink.generateCode()

        // Jediný zápis v celém repozitáři, který se čeká až na potvrzení serverem.
        // Security rules se vyhodnocují na serveru a u podkolekcí skupiny sahají
        // `get()` na její dokument. Dokud tam skupina není, každý listener na její
        // výdaje dostane PERMISSION_DENIED — takže vytvořit skupinu jen lokálně
        // by znamenalo otevřít obrazovku, která hlásí chybu, dokud se sync nedožene.
        // withContext(IO) tu není kvůli vláknu, ale kvůli času: timeout měří reálnou
        // latenci sítě, a kdyby zůstal na dispatcheru volajícího, v testech nad
        // `runTest` by běžel na virtuálním čase a vypršel by okamžitě.
        withContext(Dispatchers.IO) {
            withTimeout(CREATE_GROUP_TIMEOUT_MILLIS) {
                document.set(
                    GroupDocument(
                        name = name,
                        currency = currency.code,
                        ownerUid = user.id.value,
                        memberUids = listOf(user.id.value),
                        inviteCode = inviteCode,
                    ),
                ).await()
            }
        }

        // Zakladatel je rovnou členem — skupina bez členů by neměla komu nic účtovat.
        // Tohle už čekat nemusí, skupina na serveru existuje.
        members(GroupId(document.id))
            .document()
            .set(MemberDocument(name = creatorName, uid = user.id.value))
            .reportFailureAsync("createGroup/member")

        GroupId(document.id)
    }

    /**
     * Vstup do skupiny přes pozvánku.
     *
     * Čeká na potvrzení serverem — na rozdíl od ostatních zápisů tady o výsledku
     * rozhodují security rules, a špatný kód se pozná jedině tak, že zápis odmítnou.
     * Bez čekání by se uživateli řeklo „hotovo" a on by pak zíral na prázdný seznam.
     *
     * `joinToken` je kód, který uživatel zná; pravidlo ho porovná s uloženým
     * `inviteCode`, který nečlen přečíst nemůže.
     */
    override suspend fun joinGroup(invite: Invite, displayName: String): Result<Unit> = runCatching {
        val user = requireNotNull(auth.currentUserOrNull()) {
            "Do skupiny se dá vstoupit jen přihlášený"
        }

        withContext(Dispatchers.IO) {
            withTimeout(JOIN_TIMEOUT_MILLIS) {
                groups().document(invite.groupId.value)
                    .update(
                        mapOf(
                            "memberUids" to FieldValue.arrayUnion(user.id.value),
                            "joinToken" to invite.code,
                        ),
                    )
                    .await()
            }
        }

        // Členský záznam vzniká až po vstupu — dřív by ho pravidla odmítla.
        members(invite.groupId)
            .document()
            .set(MemberDocument(name = displayName, uid = user.id.value))
            .reportFailureAsync("joinGroup/member")
    }

    override suspend fun renameGroup(id: GroupId, name: String): Result<Unit> = runCatching {
        groups().document(id.value).update("name", name).reportFailureAsync("renameGroup")
    }

    override suspend fun archiveGroup(id: GroupId, archived: Boolean): Result<Unit> = runCatching {
        groups().document(id.value).update("archived", archived).reportFailureAsync("archiveGroup")
    }

    override suspend fun deleteGroup(id: GroupId): Result<Unit> = runCatching {
        groups().document(id.value).delete().reportFailureAsync("deleteGroup")
    }

    override suspend fun addMember(
        groupId: GroupId,
        name: String,
        defaultWeight: Int,
    ): Result<MemberId> = runCatching {
        // document() vygeneruje id klientsky, takže ho máme i bez spojení. add() by
        // vrátilo Task, jehož .result je offline nedostupný.
        val reference = members(groupId).document()
        reference.set(MemberDocument(name = name, uid = null, defaultWeight = defaultWeight))
            .reportFailureAsync("addMember")
        MemberId(reference.id)
    }

    override suspend fun updateMember(groupId: GroupId, member: Member): Result<Unit> = runCatching {
        members(groupId).document(member.id.value).set(member.toDocument())
            .reportFailureAsync("updateMember")

        // memberUids na skupině řídí přístup v security rules, takže napojení
        // virtuálního člena na účet se musí promítnout i tam.
        member.userId?.let { userId ->
            groups().document(groupId.value)
                .update("memberUids", FieldValue.arrayUnion(userId.value))
                .reportFailureAsync("updateMember/memberUids")
        }
    }

    override suspend fun removeMember(groupId: GroupId, memberId: MemberId): Result<Unit> =
        runCatching {
            val snapshot = members(groupId).document(memberId.value).get().await()
            val uid = snapshot.toObject(MemberDocument::class.java)?.uid

            members(groupId).document(memberId.value).delete()
                .reportFailureAsync("removeMember")

            uid?.takeIf { it.isNotBlank() }?.let {
                groups().document(groupId.value)
                    .update("memberUids", FieldValue.arrayRemove(it))
                    .reportFailureAsync("removeMember/memberUids")
            }
        }

    private companion object {
        /**
         * Bez připojení skupinu založit nejde (viz createGroup). Timeout je tu proto,
         * aby to skončilo srozumitelnou chybou místo nekonečného spinneru.
         */
        const val CREATE_GROUP_TIMEOUT_MILLIS = 15_000L

        /** Vstup do skupiny taky čeká na server — viz joinGroup. */
        const val JOIN_TIMEOUT_MILLIS = 15_000L

        const val GROUPS = "groups"
        const val MEMBERS = "members"
    }
}
