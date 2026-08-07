package dev.op.deptor.domain.invite

import dev.op.deptor.domain.model.GroupId
import kotlin.random.Random

/** Pozvánka do skupiny — dvojice, která stačí ke vstupu. */
data class Invite(
    val groupId: GroupId,
    val code: String,
) {
    init {
        require(groupId.value.isNotBlank()) { "Pozvánka bez skupiny nedává smysl" }
        require(code.length >= InviteLink.MIN_CODE_LENGTH) {
            "Pozvánkový kód je příliš krátký, má ${code.length} znaků"
        }
    }
}

/**
 * Odkaz, kterým se dá vstoupit do skupiny.
 *
 * Kód **je oprávnění** — kdo ho zná, může se do skupiny přidat. Musí být proto dost
 * dlouhý a náhodný na to, aby se nedal uhodnout; security rules ho porovnávají proti
 * uložené hodnotě, kterou nečlen nemá jak přečíst.
 *
 * Odkaz používá vlastní schéma `deptor://`, ne https. Klikatelný odkaz z prohlížeče
 * nebo z náhledu ve WhatsAppu by znamenal vlastní doménu a ověření App Links —
 * to je práce navázaná na webovou verzi, která zatím neexistuje. Schéma je schované
 * tady, takže přechod na https bude změna na jednom místě.
 */
object InviteLink {

    const val SCHEME = "deptor"
    const val HOST = "join"

    /** Délka generovaného kódu. 22 znaků z 32znakové abecedy je zhruba 110 bitů. */
    const val CODE_LENGTH = 22
    const val MIN_CODE_LENGTH = 12

    /** Bez znaků, které se pletou při přepisu z QR nebo z papíru (0/O, 1/I/l). */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    fun generateCode(random: Random = Random.Default): String =
        (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun build(invite: Invite): String = "$SCHEME://$HOST/${invite.groupId.value}/${invite.code}"

    /**
     * Přečte pozvánku z odkazu. Vrací `null` u čehokoli, co pozvánka není — odkaz
     * chodí zvenčí a nesmí být jak aplikaci shodit.
     */
    fun parse(link: String?): Invite? {
        val trimmed = link?.trim().orEmpty()
        val prefix = "$SCHEME://$HOST/"
        if (!trimmed.startsWith(prefix)) return null

        val parts = trimmed.removePrefix(prefix).split('/')
        if (parts.size != 2) return null

        val (groupId, code) = parts
        if (groupId.isBlank() || code.length < MIN_CODE_LENGTH) return null

        return runCatching { Invite(GroupId(groupId), code) }.getOrNull()
    }
}
