package dev.op.deptor.domain.repository

import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.MemberId
import kotlinx.coroutines.flow.Flow

interface GroupRepository {

    /** Skupiny, jichž je přihlášený uživatel členem. */
    fun observeGroups(): Flow<List<Group>>

    fun observeGroup(id: GroupId): Flow<Group?>

    suspend fun createGroup(name: String, currency: CurrencyCode, creatorName: String): Result<GroupId>

    /**
     * Vstup do skupiny přes pozvánku.
     *
     * Kód se neověřuje na klientovi — ten na cizí skupinu stejně nevidí. Ověření dělají
     * security rules porovnáním s uloženou hodnotou; při špatném kódu zápis selže.
     */
    suspend fun joinGroup(invite: Invite, displayName: String): Result<Unit>

    suspend fun renameGroup(id: GroupId, name: String): Result<Unit>

    suspend fun archiveGroup(id: GroupId, archived: Boolean): Result<Unit>

    suspend fun deleteGroup(id: GroupId): Result<Unit>

    /** Přidá člena. Virtuální člen (bez účtu) vznikne tím, že [Member.userId] je null. */
    suspend fun addMember(groupId: GroupId, name: String, defaultWeight: Int = 1): Result<MemberId>

    suspend fun updateMember(groupId: GroupId, member: Member): Result<Unit>

    suspend fun removeMember(groupId: GroupId, memberId: MemberId): Result<Unit>
}
