package dev.op.deptor.domain.model

import kotlin.jvm.JvmInline

@JvmInline
value class UserId(val value: String)

@JvmInline
value class GroupId(val value: String)

/**
 * Identita člena **ve skupině**, ne uživatele. Virtuální člen (někdo, kdo appku nemá)
 * má MemberId, ale žádné UserId — proto se všechno v doméně točí kolem MemberId.
 */
@JvmInline
value class MemberId(val value: String)

@JvmInline
value class TransactionId(val value: String)

@JvmInline
value class CategoryId(val value: String)
