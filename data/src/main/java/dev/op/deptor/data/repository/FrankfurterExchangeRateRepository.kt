package dev.op.deptor.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.money.ExchangeRate
import dev.op.deptor.domain.repository.ExchangeRateRepository
import dev.op.deptor.domain.repository.QuotedRate
import dev.op.deptor.domain.repository.RateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kurzy z Frankfurteru — veřejné API nad kurzy ECB, bez registrace a bez API klíče.
 *
 * Stažený kurz se ukládá do cache a při nedostupné síti se použije poslední známý.
 * To je záměrně důležitější, než by se zdálo: cizí měnu člověk zadává hlavně na
 * cestách, tedy přesně tam, kde signál nebývá.
 *
 * Zdroj kurzů je schovaný za [ExchangeRateRepository], takže se dá vyměnit (třeba za
 * ČNB) beze změny zbytku aplikace.
 */
@Singleton
class FrankfurterExchangeRateRepository @Inject constructor(
    @ApplicationContext context: Context,
) : ExchangeRateRepository {

    private val cache: SharedPreferences =
        context.getSharedPreferences("exchange-rates", Context.MODE_PRIVATE)

    override suspend fun rate(from: CurrencyCode, to: CurrencyCode): Result<QuotedRate> {
        if (from == to) return Result.success(QuotedRate(ExchangeRate.IDENTITY, RateSource.FRESH))

        cachedToday(from, to)?.let { return Result.success(QuotedRate(it, RateSource.FRESH)) }

        val fetched = runCatching { withContext(Dispatchers.IO) { fetch(from, to) } }
        fetched.getOrNull()?.let { rate ->
            store(from, to, rate)
            return Result.success(QuotedRate(rate, RateSource.FRESH))
        }

        // Síť selhala — poslední známý kurz je pořád lepší než nic, jen se to musí
        // dát poznat, aby si uživatel mohl kurz opravit.
        cachedAny(from, to)?.let { return Result.success(QuotedRate(it, RateSource.CACHED)) }

        return Result.failure(
            fetched.exceptionOrNull()
                ?: IllegalStateException("Kurz $from → $to se nepodařilo získat"),
        )
    }

    private fun fetch(from: CurrencyCode, to: CurrencyCode): ExchangeRate {
        val url = "$ENDPOINT?base=${from.code}&symbols=${to.code}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }

        return try {
            check(connection.responseCode in 200..299) {
                "Kurzovní API odpovědělo HTTP ${connection.responseCode}"
            }
            val body = connection.inputStream.bufferedReader().readText()
            val rates = JSONObject(body).getJSONObject("rates")

            // Přes toString, ne getDouble: BigDecimal z double by nesl binární
            // nepřesnost, kterou by pak zdědila každá převedená částka.
            ExchangeRate(BigDecimal(rates.get(to.code).toString()))
        } finally {
            connection.disconnect()
        }
    }

    private fun cachedToday(from: CurrencyCode, to: CurrencyCode): ExchangeRate? {
        val stored = cache.getString(key(from, to), null) ?: return null
        val (rate, day) = parse(stored) ?: return null
        return rate.takeIf { day == LocalDate.now().toEpochDay() }
    }

    private fun cachedAny(from: CurrencyCode, to: CurrencyCode): ExchangeRate? {
        val stored = cache.getString(key(from, to), null) ?: return null
        return parse(stored)?.first
    }

    private fun store(from: CurrencyCode, to: CurrencyCode, rate: ExchangeRate) {
        cache.edit {
            putString(key(from, to), "${rate.value.toPlainString()}|${LocalDate.now().toEpochDay()}")
        }
    }

    private fun parse(stored: String): Pair<ExchangeRate, Long>? {
        val parts = stored.split('|')
        if (parts.size != 2) return null
        val rate = ExchangeRate.parse(parts[0]).getOrNull() ?: return null
        val day = parts[1].toLongOrNull() ?: return null
        return rate to day
    }

    private fun key(from: CurrencyCode, to: CurrencyCode) = "${from.code}_${to.code}"

    private companion object {
        const val ENDPOINT = "https://api.frankfurter.dev/v1/latest"
        const val TIMEOUT_MILLIS = 10_000
    }
}
