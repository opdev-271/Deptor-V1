package dev.op.deptor.domain.invite

import dev.op.deptor.domain.model.GroupId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteLinkTest {

    private val invite = Invite(GroupId("g123"), "abcdefghjkmn")

    @Test
    fun `cesta tam a zpet`() {
        assertEquals(invite, InviteLink.parse(InviteLink.build(invite)))
    }

    @Test
    fun `odkaz obsahuje skupinu i kod`() {
        val link = InviteLink.build(invite)
        assertTrue(link.contains("g123"))
        assertTrue(link.contains("abcdefghjkmn"))
    }

    @Test
    fun `generovany kod je dost dlouhy a v abecede`() {
        val code = InviteLink.generateCode(Random(1))
        assertEquals(InviteLink.CODE_LENGTH, code.length)
        // Znaky, které se pletou při přepisu (0/O, 1/I/l), v abecedě nejsou.
        assertTrue(code.none { it in "01oil" }, "kód obsahuje matoucí znak: $code")
    }

    @Test
    fun `generovane kody se neopakuji`() {
        val codes = List(1_000) { InviteLink.generateCode() }
        assertEquals(codes.size, codes.toSet().size, "kód se zopakoval")
    }

    @Test
    fun `nesmyslny vstup vrati null misto padu`() {
        // Odkaz chodí zvenčí, takže cokoli sem může přijít.
        listOf(
            null,
            "",
            "   ",
            "https://example.com",
            "deptor://neco/jineho",
            "deptor://join/",
            "deptor://join/onlygroup",
            "deptor://join/g1/kratky",
            "deptor://join/g1/abcdefghjkmn/navic",
            "deptor://join//abcdefghjkmn",
            "úplný nesmysl",
        ).forEach { input ->
            assertNull(InviteLink.parse(input), "mělo vyjít null pro '$input'")
        }
    }

    @Test
    fun `okoli odkazu se orizne`() {
        assertEquals(invite, InviteLink.parse("  ${InviteLink.build(invite)}  "))
    }

    @Test
    fun `prilis kratky kod se odmitne uz v konstruktoru`() {
        assertFailsWith<IllegalArgumentException> { Invite(GroupId("g1"), "kratky") }
    }

    @Test
    fun `pozvanka bez skupiny se odmitne`() {
        assertFailsWith<IllegalArgumentException> { Invite(GroupId(""), "abcdefghjkmn") }
    }
}
