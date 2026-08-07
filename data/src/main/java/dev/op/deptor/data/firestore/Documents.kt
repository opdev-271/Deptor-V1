package dev.op.deptor.data.firestore

/**
 * Tvar dokumentů ve Firestore.
 *
 * Všechna pole mají výchozí hodnotu, protože Firestore vrací to, co v databázi
 * skutečně je — starší dokument, dokument od klienta s jinou verzí schématu nebo
 * ručně upravený záznam klidně pole postrádá. Mapování na doménu je proto úmyslně
 * obranné a chybějící či nesmyslná data odmítne, místo aby dosadilo nulu.
 *
 * Objekty jsou `data class` s prázdným konstruktorem, aby je uměl deserializovat
 * Firestore SDK.
 */
data class TransactionDocument(
    val type: String = "",
    val amountMinor: Long = 0,
    val currency: String = "",
    val date: com.google.firebase.Timestamp? = null,
    val description: String = "",
    val payers: List<EntryDocument> = emptyList(),
    val splits: List<EntryDocument> = emptyList(),
    val splitMethod: String = "EQUAL",
    /**
     * Kurz na měnu skupiny jako **text**, ne číslo. Firestore umí jen `Double`,
     * což by u kurzu typu 24,375 zaneslo do částek zaokrouhlovací chybu hned
     * na vstupu. Prázdný řetězec znamená, že kurz není potřeba.
     */
    val exchangeRate: String = "",
    val categoryId: String? = null,
    val note: String? = null,
    val createdBy: String? = null,
    val createdAt: com.google.firebase.Timestamp? = null,
)

/** Jedna položka v `payers` nebo `splits`. */
data class EntryDocument(
    val memberId: String = "",
    val amountMinor: Long = 0,
)

data class GroupDocument(
    val name: String = "",
    val currency: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val archived: Boolean = false,
    /** Kód pozvánky. Nečlen ho nepřečte — skupina pro něj není čitelná. */
    val inviteCode: String = "",
    /**
     * Kód, který posílá ten, kdo do skupiny vstupuje. Security rules ho porovnají
     * s [inviteCode]; slouží jen k ověření a jinak se nepoužívá.
     */
    val joinToken: String = "",
    val createdAt: com.google.firebase.Timestamp? = null,
)

data class MemberDocument(
    val name: String = "",
    val uid: String? = null,
    val defaultWeight: Int = 1,
    val active: Boolean = true,
)
