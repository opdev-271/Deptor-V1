package dev.op.deptor.data.firestore

import com.google.firebase.Timestamp
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MappersTest {

    private val groupId = GroupId("g1")

    private fun doc(
        type: String = "EXPENSE",
        amount: Long = 900,
        currency: String = "CZK",
        payers: List<EntryDocument> = listOf(EntryDocument("anna", 900)),
        splits: List<EntryDocument> = listOf(
            EntryDocument("anna", 300),
            EntryDocument("bob", 300),
            EntryDocument("cyril", 300),
        ),
        date: Timestamp? = Timestamp(1_700_000_000, 0),
        splitMethod: String = "EQUAL",
    ) = TransactionDocument(
        type = type,
        amountMinor = amount,
        currency = currency,
        date = date,
        description = "oběd",
        payers = payers,
        splits = splits,
        splitMethod = splitMethod,
    )

    @Test
    fun `platny dokument se prelozi`() {
        val transaction = doc().toDomain("t1", groupId).getOrThrow()

        assertEquals(TransactionType.EXPENSE, transaction.type)
        assertEquals(Money(900), transaction.amount)
        assertEquals(CurrencyCode.CZK, transaction.currency)
        assertEquals(3, transaction.splits.size)
        assertEquals(MemberId("anna"), transaction.payers.single().memberId)
    }

    @Test
    fun `dokument s rozbitym souctem podilu se odmitne`() {
        // Přesně to, co security rules zachytit neumí: součet podílů neodpovídá částce.
        // Kdyby takový záznam prošel, salda skupiny by tiše přestala vycházet.
        val result = doc(
            splits = listOf(EntryDocument("anna", 300), EntryDocument("bob", 300)),
        ).toDomain("t1", groupId)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("podílů") == true,
            "chybová hláška má říct, co je špatně: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `dokument s rozbitym souctem platcu se odmitne`() {
        val result = doc(payers = listOf(EntryDocument("anna", 500))).toDomain("t1", groupId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `neznamy typ transakce se odmitne`() {
        val result = doc(type = "SOMETHING_ELSE").toDomain("t1", groupId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `chybejici datum se odmitne`() {
        val result = doc(date = null).toDomain("t1", groupId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `nesmyslna mena se odmitne`() {
        val result = doc(currency = "Kc").toDomain("t1", groupId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `zaporna castka se odmitne`() {
        val result = doc(
            amount = -900,
            payers = listOf(EntryDocument("anna", -900)),
            splits = listOf(EntryDocument("bob", -900)),
        ).toDomain("t1", groupId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `prazdna transakce se odmitne`() {
        val result = doc(payers = emptyList(), splits = emptyList()).toDomain("t1", groupId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `neznama metoda rozdeleni spadne na EQUAL misto odmitnuti`() {
        // Metoda slouží jen k rekonstrukci formuláře, na výpočet nemá vliv —
        // kvůli ní nemá smysl zahazovat jinak platnou transakci.
        val transaction = doc(splitMethod = "NEJAKA_NOVA").toDomain("t1", groupId).getOrThrow()
        assertEquals(dev.op.deptor.domain.model.SplitMethod.EQUAL, transaction.splitMethod)
    }

    @Test
    fun `cesta tam a zpet zachova data`() {
        val original = doc().toDomain("t1", groupId).getOrThrow()
        val roundTripped = original.toDocument().toDomain("t1", groupId).getOrThrow()
        assertEquals(original, roundTripped)
    }

    @Test
    fun `memberUids obsahuje jen skutecne ucty`() {
        // Virtuální člen nemá uid a nesmí se dostat do memberUids — to pole
        // řídí přístup v security rules.
        val group = dev.op.deptor.domain.model.Group(
            id = groupId,
            name = "Chata",
            currency = CurrencyCode.CZK,
            ownerUid = dev.op.deptor.domain.model.UserId("uid-anna"),
            members = listOf(
                dev.op.deptor.domain.model.Member(
                    id = MemberId("anna"),
                    name = "Anna",
                    userId = dev.op.deptor.domain.model.UserId("uid-anna"),
                ),
                dev.op.deptor.domain.model.Member(id = MemberId("bob"), name = "Bob"),
            ),
        )

        assertEquals(listOf("uid-anna"), group.toDocument().memberUids)
    }

    @Test
    fun `virtualni clen se prelozi bez uid`() {
        val member = MemberDocument(name = "Bob", uid = null).toDomain("bob").getOrThrow()
        assertTrue(member.isVirtual)
    }

    @Test
    fun `prazdne uid se bere jako virtualni clen`() {
        val member = MemberDocument(name = "Bob", uid = "").toDomain("bob").getOrThrow()
        assertTrue(member.isVirtual)
    }

    @Test
    fun `clen bez jmena se odmitne`() {
        assertTrue(MemberDocument(name = "").toDomain("bob").isFailure)
    }

    @Test
    fun `timestamp prezije prevod tam a zpet`() {
        val timestamp = Timestamp(1_700_000_000, 123_456_789)
        assertEquals(timestamp, timestamp.toInstant().toTimestamp())
    }
}
