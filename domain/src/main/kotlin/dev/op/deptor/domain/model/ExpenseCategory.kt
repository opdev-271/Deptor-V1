package dev.op.deptor.domain.model

/**
 * Kategorie výdaje.
 *
 * Seznam je pevný, ne uživatelsky editovatelný. Vlastní kategorie by znamenaly další
 * kolekci ve Firestore, správu duplicit mezi členy a slučování při synchronizaci —
 * a přehledy typu „na co jsme utratili nejvíc" fungují dobře i nad pevnou sadou.
 *
 * [id] je **stabilní řetězec, ne pořadí v enumu**: ukládá se do databáze, takže
 * přejmenování konstanty ani vložení nové kategorie doprostřed nesmí rozhodit
 * už uložené výdaje.
 *
 * Zobrazované názvy a ikony jsou v UI vrstvě — doména o vzhledu nic neví.
 */
enum class ExpenseCategory(val id: CategoryId) {
    FOOD(CategoryId("food")),
    GROCERIES(CategoryId("groceries")),
    TRANSPORT(CategoryId("transport")),
    ACCOMMODATION(CategoryId("accommodation")),
    ENTERTAINMENT(CategoryId("entertainment")),
    SHOPPING(CategoryId("shopping")),
    HEALTH(CategoryId("health")),
    UTILITIES(CategoryId("utilities")),
    OTHER(CategoryId("other")),
    ;

    companion object {
        /**
         * Kategorie podle uloženého id, nebo `null` u výdaje bez kategorie i u id,
         * které aplikace nezná — třeba když ho zapsala novější verze. Neznámá
         * kategorie se pak chová jako nezařazený výdaj, což je lepší než pád.
         */
        fun fromId(id: CategoryId?): ExpenseCategory? =
            id?.let { value -> entries.firstOrNull { it.id == value } }
    }
}
