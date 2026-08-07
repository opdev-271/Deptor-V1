package dev.op.deptor.domain.repository

import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.money.ExchangeRate

/** Odkud kurz pochází — UI podle toho pozná, nakolik je aktuální. */
enum class RateSource {
    /** Čerstvě stažený kurz. */
    FRESH,

    /** Poslední známý kurz z cache; typicky offline. */
    CACHED,
}

data class QuotedRate(
    val rate: ExchangeRate,
    val source: RateSource,
)

interface ExchangeRateRepository {

    /**
     * Kurz z [from] na [to].
     *
     * Selže jen tehdy, když kurz není ani na síti, ani v cache — v takovém případě
     * musí uživatel zadat kurz ručně, protože bez něj by transakci nešlo započítat
     * do sald.
     */
    suspend fun rate(from: CurrencyCode, to: CurrencyCode): Result<QuotedRate>
}
