package dev.op.deptor.domain.model

/**
 * Člen skupiny.
 *
 * @param userId `null` u virtuálního člena — tedy někoho, koho někdo přidal jen jménem
 *   a kdo appku nemá. Později se dá napojit na skutečný účet doplněním userId,
 *   aniž by se musely přepisovat transakce (ty odkazují na [id], ne na uživatele).
 * @param defaultWeight výchozí váha při rozdělování rovným dílem, např. dítě = 1 proti
 *   dospělému = 2. Váha 0 znamená, že se člen do rovného dělení nezapočítává.
 */
data class Member(
    val id: MemberId,
    val name: String,
    val userId: UserId? = null,
    val defaultWeight: Int = 1,
    val active: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "Jméno člena nesmí být prázdné" }
        require(defaultWeight >= 0) { "Váha nesmí být záporná, dostal jsem $defaultWeight" }
    }

    val isVirtual: Boolean get() = userId == null
}

data class Group(
    val id: GroupId,
    val name: String,
    val currency: CurrencyCode,
    val ownerUid: UserId,
    val members: List<Member> = emptyList(),
    val archived: Boolean = false,
    /**
     * Kód, kterým se dá do skupiny vstoupit. `null` u skupin, které ho ještě nemají.
     * Je to oprávnění — nečlen si ho nemá jak přečíst, viz security rules.
     */
    val inviteCode: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Název skupiny nesmí být prázdný" }
        require(members.map { it.id }.toSet().size == members.size) {
            "Skupina obsahuje duplicitní MemberId"
        }
    }

    fun member(id: MemberId): Member? = members.firstOrNull { it.id == id }
}
