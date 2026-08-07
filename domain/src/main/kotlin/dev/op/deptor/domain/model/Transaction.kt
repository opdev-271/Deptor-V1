package dev.op.deptor.domain.model

import dev.op.deptor.domain.money.ExchangeRate
import java.time.Instant

/** Kolik daný člen na transakci reálně vložil (zaplatil). */
data class Payer(val memberId: MemberId, val amount: Money)

/** Kolik daného člena transakce reálně stojí (jeho podíl). */
data class Split(val memberId: MemberId, val amount: Money)

enum class TransactionType {
    /** Běžný výdaj — někdo zaplatil, rozpočítá se mezi členy. */
    EXPENSE,

    /** Vyrovnání dluhu — A předal peníze B. */
    SETTLEMENT,

    /** Příjem do skupiny (sleva, výhra, vratka) — rozpočítá se opačně. */
    INCOME,
}

/**
 * Jak bylo rozdělení zadáno. Slouží **jen** k rekonstrukci formuláře při editaci —
 * výpočet bilance metodu nezná a nepotřebuje, protože [Transaction.splits] jsou
 * vždy už hotové částky.
 */
enum class SplitMethod { EQUAL, EXACT, PERCENT, SHARES, ITEMS }

/**
 * Jedna položka v historii skupiny.
 *
 * Klíčové rozhodnutí návrhu: [splits] jsou uložené jako **konkrétní částky**, ne jako
 * pravidlo k pozdějšímu vyhodnocení. Metoda rozdělení se vyhodnotí jednou při zadání.
 * Díky tomu je výpočet bilance jeden a tentýž součet pro všech pět metod rozdělení
 * ze zadání a settlement není zvláštní případ, ale obyčejná transakce.
 *
 * Invariant vynucený v [init]: součet [payers] == součet [splits] == [amount].
 * Kdyby neplatil, salda by se nikdy nesečetla na nulu.
 */
data class Transaction(
    val id: TransactionId,
    val groupId: GroupId,
    val type: TransactionType,
    val amount: Money,
    val currency: CurrencyCode,
    val date: Instant,
    val description: String,
    val payers: List<Payer>,
    val splits: List<Split>,
    val splitMethod: SplitMethod = SplitMethod.EQUAL,
    /**
     * Kurz na měnu skupiny platný v době zadání, nebo `null`, když je transakce
     * rovnou v měně skupiny a převod není potřeba.
     *
     * Nullable schválně: kdyby chybějící kurz mlčky znamenal 1, transakce v eurech
     * bez kurzu by se do sald započítala jako koruny a nikdo by si toho nevšiml.
     * Takhle si o něj převod musí říct. Kurz se drží u transakce a nepřepočítává
     * se později — viz [ExchangeRate].
     */
    val exchangeRate: ExchangeRate? = null,
    val categoryId: CategoryId? = null,
    val note: String? = null,
    val createdBy: UserId? = null,
    val createdAt: Instant = date,
) {
    init {
        require(payers.isNotEmpty()) { "Transakce musí mít alespoň jednoho plátce" }
        require(splits.isNotEmpty()) { "Transakce musí mít alespoň jeden podíl" }
        require(amount.isPositive) {
            "Částka musí být kladná (typ transakce určuje směr), dostal jsem $amount"
        }

        val paid = payers.sumOfMoney { it.amount }
        val owed = splits.sumOfMoney { it.amount }
        require(paid == amount) {
            "Součet plátců ($paid) neodpovídá částce transakce ($amount)"
        }
        require(owed == amount) {
            "Součet podílů ($owed) neodpovídá částce transakce ($amount)"
        }

        require(payers.map { it.memberId }.toSet().size == payers.size) {
            "Duplicitní plátce v transakci"
        }
        require(splits.map { it.memberId }.toSet().size == splits.size) {
            "Duplicitní podíl v transakci"
        }
    }

    /** Členové, kterých se transakce jakkoli týká. */
    val involvedMembers: Set<MemberId>
        get() = payers.map { it.memberId }.toSet() + splits.map { it.memberId }.toSet()
}
