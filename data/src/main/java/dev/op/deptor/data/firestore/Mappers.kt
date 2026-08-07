package dev.op.deptor.data.firestore

import com.google.firebase.Timestamp
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Payer
import dev.op.deptor.domain.model.Split
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.model.SplitMethod
import dev.op.deptor.domain.model.CategoryId
import dev.op.deptor.domain.model.UserId
import dev.op.deptor.domain.money.ExchangeRate
import java.time.Instant

/**
 * Převod mezi tvarem ve Firestore a doménou.
 *
 * Směr **do domény** je záměrně přísný. Security rules neumí ověřit, že součet plátců
 * a podílů odpovídá částce — jazyk rules nemá smyčky ani reduce, takže pole libovolné
 * délky v nich sečíst nejde. Jediné místo, kde se dá invariant uhlídat na straně čtení,
 * je tenhle mapper: konstruktor [Transaction] invariant vynucuje, takže vadný dokument
 * skončí jako [Result.failure] a nedostane se do výpočtu sald.
 *
 * Kdyby se místo toho jen dosadily nuly, salda by tiše vyšla špatně a nikde by to
 * nebylo vidět — proto se tu nikdy nic „neopravuje", jen odmítá.
 */

fun TransactionDocument.toDomain(id: String, groupId: GroupId): Result<Transaction> = runCatching {
    val type = requireNotNull(TransactionType.entries.firstOrNull { it.name == type }) {
        "Neznámý typ transakce '${this.type}'"
    }
    val method = SplitMethod.entries.firstOrNull { it.name == splitMethod } ?: SplitMethod.EQUAL
    val date = requireNotNull(date) { "Transakce nemá datum" }.toInstant()

    Transaction(
        id = TransactionId(id),
        groupId = groupId,
        type = type,
        amount = Money(amountMinor),
        currency = CurrencyCode(currency),
        date = date,
        description = description,
        payers = payers.map { Payer(MemberId(it.memberId), Money(it.amountMinor)) },
        splits = splits.map { Split(MemberId(it.memberId), Money(it.amountMinor)) },
        splitMethod = method,
        exchangeRate = exchangeRate.takeIf { it.isNotBlank() }
            ?.let { ExchangeRate.parse(it).getOrThrow() },
        categoryId = categoryId?.takeIf { it.isNotBlank() }?.let(::CategoryId),
        note = note?.takeIf { it.isNotBlank() },
        createdBy = createdBy?.takeIf { it.isNotBlank() }?.let(::UserId),
        createdAt = createdAt?.toInstant() ?: date,
    )
}

fun Transaction.toDocument(): TransactionDocument = TransactionDocument(
    type = type.name,
    amountMinor = amount.minor,
    currency = currency.code,
    date = date.toTimestamp(),
    description = description,
    payers = payers.map { EntryDocument(it.memberId.value, it.amount.minor) },
    splits = splits.map { EntryDocument(it.memberId.value, it.amount.minor) },
    splitMethod = splitMethod.name,
    exchangeRate = exchangeRate?.value?.toPlainString().orEmpty(),
    categoryId = categoryId?.value,
    note = note,
    createdBy = createdBy?.value,
    createdAt = createdAt.toTimestamp(),
)

fun GroupDocument.toDomain(id: String, members: List<Member>): Result<Group> = runCatching {
    Group(
        id = GroupId(id),
        name = name,
        currency = CurrencyCode(currency),
        ownerUid = UserId(ownerUid),
        members = members,
        archived = archived,
        inviteCode = inviteCode.takeIf { it.isNotBlank() },
    )
}

fun Group.toDocument(): GroupDocument = GroupDocument(
    name = name,
    currency = currency.code,
    ownerUid = ownerUid.value,
    memberUids = members.mapNotNull { it.userId?.value }.distinct(),
    archived = archived,
    inviteCode = inviteCode.orEmpty(),
)

fun MemberDocument.toDomain(id: String): Result<Member> = runCatching {
    Member(
        id = MemberId(id),
        name = name,
        userId = uid?.takeIf { it.isNotBlank() }?.let(::UserId),
        defaultWeight = defaultWeight,
        active = active,
    )
}

fun Member.toDocument(): MemberDocument = MemberDocument(
    name = name,
    uid = userId?.value,
    defaultWeight = defaultWeight,
    active = active,
)

// Timestamp.toInstant() poskytuje samo Firebase SDK, opačný směr ne.
internal fun Instant.toTimestamp(): Timestamp = Timestamp(epochSecond, nano)
