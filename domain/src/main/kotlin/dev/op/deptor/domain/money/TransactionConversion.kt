package dev.op.deptor.domain.money

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.split.distribute

/**
 * Převede transakci do měny skupiny podle kurzu uloženého u transakce.
 *
 * Nestačí převést každý podíl zvlášť: zaokrouhlení by se u každého posunulo jinam
 * a součty by přestaly sedět na celkovou částku, čímž by padl invariant, na kterém
 * stojí celý výpočet sald. Převede se proto **jen celková částka** a ta se pak mezi
 * plátce a podíly rozdělí v původním poměru toutéž metodou největšího zbytku, jaká
 * se používá při rozúčtování. Součty tak sedí přesně i po převodu.
 */
fun Transaction.convertedTo(target: CurrencyCode): Transaction {
    if (currency == target) return this

    val rate = requireNotNull(exchangeRate) {
        "Transakce ${id.value} je v měně $currency, ale nemá uložený kurz na $target"
    }

    // Drobná částka v silnější měně by se zaokrouhlila na nulu a transakce by přestala
    // být platná. Ztratit ji úplně je horší než být o jednu nejmenší jednotku vedle.
    val convertedAmount = rate.convert(amount, currency, target)
        .let { if (it.isZero) Money(1) else it }

    val payerAmounts = distribute(convertedAmount, payers.map { it.amount.minor })
    val splitAmounts = distribute(convertedAmount, splits.map { it.amount.minor })

    return copy(
        amount = convertedAmount,
        currency = target,
        exchangeRate = null,
        payers = payers.mapIndexed { index, payer -> payer.copy(amount = payerAmounts[index]) },
        splits = splits.mapIndexed { index, split -> split.copy(amount = splitAmounts[index]) },
    )
}

/** Byla transakce zadaná v jiné měně, než v jaké skupina počítá? */
fun Transaction.isForeign(groupCurrency: CurrencyCode): Boolean = currency != groupCurrency

/**
 * Jde transakci převést do měny skupiny?
 *
 * Volající si tím může vadné záznamy odfiltrovat a **dát o nich vědět**, místo aby
 * mu spadl výpočet sald — chybějící kurz je stejná kategorie problému jako rozbitý
 * dokument a patří uživateli na oči, ne do ztracena.
 */
fun Transaction.canConvertTo(target: CurrencyCode): Boolean =
    currency == target || exchangeRate != null
