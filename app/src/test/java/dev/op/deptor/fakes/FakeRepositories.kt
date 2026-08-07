package dev.op.deptor.fakes

import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.invite.InviteLink
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.UserId
import dev.op.deptor.domain.money.ExchangeRate
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceId
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.RecurrenceRepository
import dev.op.deptor.domain.repository.ExchangeRateRepository
import dev.op.deptor.domain.repository.QuotedRate
import dev.op.deptor.domain.repository.RateSource
import dev.op.deptor.domain.repository.AuthUser
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.domain.repository.RejectedDocument
import dev.op.deptor.domain.repository.TransactionLoad
import dev.op.deptor.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory náhrady repozitářů pro testy ViewModelů.
 *
 * Chovají se jako Firestore v tom podstatném — jsou to živé proudy, takže test vidí
 * stejnou posloupnost stavů jako skutečná obrazovka. Co nedělají, je síť: díky tomu
 * běží testy na JVM během vteřin a nepotřebují emulátor.
 */
class FakeAuthRepository(
    initialUser: AuthUser? = AuthUser(UserId("uid-ja"), "Já", null, isAnonymous = true),
) : AuthRepository {

    private val user = MutableStateFlow(initialUser)

    override val currentUser: Flow<AuthUser?> = user

    override fun currentUserOrNull(): AuthUser? = user.value

    override suspend fun signInAnonymously(): Result<AuthUser> {
        val signedIn = AuthUser(UserId("uid-anon"), null, null, isAnonymous = true)
        user.value = signedIn
        return Result.success(signedIn)
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
        if (password == WRONG_PASSWORD) {
            return Result.failure(IllegalStateException("The password is invalid"))
        }
        val signedIn = AuthUser(UserId("uid-$email"), null, email, isAnonymous = false)
        user.value = signedIn
        return Result.success(signedIn)
    }

    override suspend fun registerWithEmail(
        email: String,
        password: String,
        displayName: String,
    ): Result<AuthUser> {
        val created = AuthUser(UserId("uid-$email"), displayName, email, isAnonymous = false)
        user.value = created
        return Result.success(created)
    }

    override suspend fun linkWithEmail(email: String, password: String): Result<AuthUser> {
        val current = user.value ?: return Result.failure(IllegalStateException("Nikdo není přihlášen"))
        val upgraded = current.copy(email = email, isAnonymous = false)
        user.value = upgraded
        return Result.success(upgraded)
    }

    override suspend fun signOut() {
        user.value = null
    }

    companion object {
        const val WRONG_PASSWORD = "spatne-heslo"
    }
}

class FakeGroupRepository(
    private val auth: FakeAuthRepository = FakeAuthRepository(),
) : GroupRepository {

    val groups = MutableStateFlow<List<Group>>(emptyList())

    /** Nastavením zařídí, že další zápis selže — pro testy chybových stavů. */
    var failNextWrite: Throwable? = null

    private val ids = AtomicInteger()

    override fun observeGroups(): Flow<List<Group>> = groups

    override fun observeGroup(id: GroupId): Flow<Group?> =
        groups.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun createGroup(
        name: String,
        currency: CurrencyCode,
        creatorName: String,
    ): Result<GroupId> = write {
        val id = GroupId("g${ids.incrementAndGet()}")
        val owner = auth.currentUserOrNull()?.id ?: UserId("uid-ja")
        groups.value += Group(
            id = id,
            name = name,
            currency = currency,
            ownerUid = owner,
            members = listOf(Member(MemberId("m${ids.incrementAndGet()}"), creatorName, owner)),
            inviteCode = InviteLink.generateCode(),
        )
        id
    }

    /** Napodobuje security rules: špatný kód znamená odmítnutí. */
    override suspend fun joinGroup(invite: Invite, displayName: String): Result<Unit> = write {
        val group = groups.value.firstOrNull { it.id == invite.groupId }
            ?: error("Skupina neexistuje")
        check(group.inviteCode == invite.code) { "Neplatný pozvánkový kód" }

        val userId = auth.currentUserOrNull()?.id ?: UserId("uid-ja")
        update(invite.groupId) {
            it.copy(members = it.members + Member(MemberId("m${ids.incrementAndGet()}"), displayName, userId))
        }
    }

    override suspend fun renameGroup(id: GroupId, name: String): Result<Unit> = write {
        update(id) { it.copy(name = name) }
    }

    override suspend fun archiveGroup(id: GroupId, archived: Boolean): Result<Unit> = write {
        update(id) { it.copy(archived = archived) }
    }

    override suspend fun deleteGroup(id: GroupId): Result<Unit> = write {
        groups.value = groups.value.filterNot { it.id == id }
    }

    override suspend fun addMember(
        groupId: GroupId,
        name: String,
        defaultWeight: Int,
    ): Result<MemberId> = write {
        val memberId = MemberId("m${ids.incrementAndGet()}")
        update(groupId) {
            it.copy(members = it.members + Member(memberId, name, null, defaultWeight))
        }
        memberId
    }

    override suspend fun updateMember(groupId: GroupId, member: Member): Result<Unit> = write {
        update(groupId) { group ->
            group.copy(members = group.members.map { if (it.id == member.id) member else it })
        }
    }

    override suspend fun removeMember(groupId: GroupId, memberId: MemberId): Result<Unit> = write {
        update(groupId) { group ->
            group.copy(members = group.members.filterNot { it.id == memberId })
        }
    }

    private fun update(id: GroupId, transform: (Group) -> Group) {
        groups.value = groups.value.map { if (it.id == id) transform(it) else it }
    }

    private inline fun <T> write(action: () -> T): Result<T> {
        failNextWrite?.let {
            failNextWrite = null
            return Result.failure(it)
        }
        return runCatching(action)
    }
}

/**
 * Kurzy pro testy. Výchozí kurz je 25, aby se snadno počítalo z hlavy;
 * [failing] simuluje offline stav bez cache.
 */
class FakeExchangeRateRepository(
    private val rate: ExchangeRate = ExchangeRate(java.math.BigDecimal("25")),
    private val source: RateSource = RateSource.FRESH,
    var failing: Boolean = false,
) : ExchangeRateRepository {

    override suspend fun rate(from: CurrencyCode, to: CurrencyCode): Result<QuotedRate> = when {
        from == to -> Result.success(QuotedRate(ExchangeRate.IDENTITY, RateSource.FRESH))
        failing -> Result.failure(IllegalStateException("bez připojení"))
        else -> Result.success(QuotedRate(rate, source))
    }
}

class FakeRecurrenceRepository : RecurrenceRepository {

    val recurrences = MutableStateFlow<List<Recurrence>>(emptyList())
    private val ids = AtomicInteger()

    override fun observeRecurrences(groupId: GroupId): Flow<List<Recurrence>> =
        recurrences.map { list -> list.filter { it.groupId == groupId } }

    override suspend fun addRecurrence(recurrence: Recurrence): Result<RecurrenceId> {
        val id = RecurrenceId("r${ids.incrementAndGet()}")
        recurrences.value += recurrence.copy(id = id)
        return Result.success(id)
    }

    override suspend fun deleteRecurrence(groupId: GroupId, id: RecurrenceId): Result<Unit> {
        recurrences.value = recurrences.value.filterNot { it.id == id }
        return Result.success(Unit)
    }

    override suspend fun markGenerated(
        groupId: GroupId,
        id: RecurrenceId,
        through: java.time.LocalDate,
    ): Result<Unit> {
        recurrences.value = recurrences.value.map {
            if (it.id == id) it.copy(generatedThrough = through) else it
        }
        return Result.success(Unit)
    }
}

class FakeTransactionRepository : TransactionRepository {

    val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val rejected = MutableStateFlow<List<RejectedDocument>>(emptyList())

    var failNextWrite: Throwable? = null

    private val ids = AtomicInteger()

    override fun observeTransactions(groupId: GroupId): Flow<TransactionLoad> =
        transactions.map { list ->
            TransactionLoad(
                transactions = list.filter { it.groupId == groupId }.sortedByDescending { it.date },
                rejected = rejected.value,
            )
        }

    override suspend fun addTransaction(transaction: Transaction): Result<TransactionId> {
        failNextWrite?.let {
            failNextWrite = null
            return Result.failure(it)
        }
        // Stejně jako Firestore: vyplněné id se respektuje, aby šlo psát idempotentně.
        val id = transaction.id.takeIf { it.value.isNotBlank() }
            ?: TransactionId("t${ids.incrementAndGet()}")

        transactions.value = transactions.value.filterNot { it.id == id } + transaction.copy(id = id)
        return Result.success(id)
    }

    override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
        transactions.value = transactions.value.map {
            if (it.id == transaction.id) transaction else it
        }
        return Result.success(Unit)
    }

    override suspend fun deleteTransaction(groupId: GroupId, id: TransactionId): Result<Unit> {
        transactions.value = transactions.value.filterNot { it.id == id }
        return Result.success(Unit)
    }
}
