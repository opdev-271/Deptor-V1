package dev.op.deptor.domain

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Payer
import dev.op.deptor.domain.model.Split
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.split.ExpenseSplitter
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Stavitelé testovacích transakcí, ať testy říkají co se stalo, ne jak se to poskládá. */
object TestData {

    val anna = MemberId("a-anna")
    val bob = MemberId("b-bob")
    val cyril = MemberId("c-cyril")

    private val group = GroupId("g1")
    private val counter = AtomicInteger()

    private fun nextId() = TransactionId("t${counter.incrementAndGet()}")

    fun expense(
        payer: MemberId,
        amount: Long,
        between: List<MemberId>,
        description: String = "výdaj",
    ): Transaction = expense(listOf(payer to amount), amount, between, description)

    fun expense(
        payers: List<Pair<MemberId, Long>>,
        amount: Long,
        between: List<MemberId>,
        description: String = "výdaj",
    ): Transaction = Transaction(
        id = nextId(),
        groupId = group,
        type = TransactionType.EXPENSE,
        amount = Money(amount),
        currency = CurrencyCode.CZK,
        date = Instant.EPOCH,
        description = description,
        payers = payers.map { (member, paid) -> Payer(member, Money(paid)) },
        splits = ExpenseSplitter.equally(Money(amount), between),
    )

    fun settlement(from: MemberId, to: MemberId, amount: Long): Transaction = Transaction(
        id = nextId(),
        groupId = group,
        type = TransactionType.SETTLEMENT,
        amount = Money(amount),
        currency = CurrencyCode.CZK,
        date = Instant.EPOCH,
        description = "vyrovnání",
        payers = listOf(Payer(from, Money(amount))),
        splits = listOf(Split(to, Money(amount))),
    )

    fun income(receiver: MemberId, amount: Long, between: List<MemberId>): Transaction = Transaction(
        id = nextId(),
        groupId = group,
        type = TransactionType.INCOME,
        amount = Money(amount),
        currency = CurrencyCode.CZK,
        date = Instant.EPOCH,
        description = "příjem",
        payers = listOf(Payer(receiver, Money(amount))),
        splits = ExpenseSplitter.equally(Money(amount), between),
    )
}
