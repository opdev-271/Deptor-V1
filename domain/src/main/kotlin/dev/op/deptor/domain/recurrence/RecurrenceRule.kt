package dev.op.deptor.domain.recurrence

import java.time.LocalDate

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * Jak často se transakce opakuje.
 *
 * @param interval každé N-té období — `MONTHLY` s intervalem 3 znamená čtvrtletně.
 * @param startDate první termín; opakování se počítá vždy od něj (viz [occurrence]).
 * @param endDate poslední den, kdy se ještě opakuje. `null` = bez konce.
 */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val startDate: LocalDate,
    val interval: Int = 1,
    val endDate: LocalDate? = null,
) {
    init {
        require(interval >= 1) { "Interval musí být aspoň 1, dostal jsem $interval" }
        require(endDate == null || !endDate.isBefore(startDate)) {
            "Konec opakování nesmí být před začátkem"
        }
    }

    /**
     * N-tý termín, počítaný **vždy od začátku**, ne přičítáním k předchozímu.
     *
     * Na tom záleží víc, než se zdá: nájem od 31. ledna měsíčně má padnout na
     * 28. února a pak zase na 31. března. Kdyby se přičítalo k předchozímu termínu,
     * únor by datum ořízl na 28. a od té chvíle by nájem chodil 28. každý měsíc.
     * `plusMonths` od začátku ořízne jen tam, kde je to nutné.
     *
     * Stejný důvod platí pro 29. února u ročního opakování.
     */
    fun occurrence(index: Int): LocalDate {
        val step = (index.toLong()) * interval
        return when (frequency) {
            RecurrenceFrequency.DAILY -> startDate.plusDays(step)
            RecurrenceFrequency.WEEKLY -> startDate.plusWeeks(step)
            RecurrenceFrequency.MONTHLY -> startDate.plusMonths(step)
            RecurrenceFrequency.YEARLY -> startDate.plusYears(step)
        }
    }

    /**
     * Termíny, které už měly proběhnout, ale ještě nebyly vytvořené.
     *
     * @param today dnešek; budoucí termíny se negenerují.
     * @param generatedThrough poslední už vytvořený termín, nebo `null` u nového pravidla.
     *
     * Počet termínů na jedno volání je omezený: pravidlo staré roky by jinak při prvním
     * otevření aplikace vysypalo tisíce transakcí naráz. Zbytek se dožene při dalším
     * spuštění, protože `generatedThrough` mezitím poskočí.
     */
    fun dueOccurrences(
        today: LocalDate,
        generatedThrough: LocalDate? = null,
        limit: Int = MAX_PER_RUN,
    ): List<LocalDate> {
        val last = endDate?.let { if (it.isBefore(today)) it else today } ?: today
        val result = mutableListOf<LocalDate>()

        var index = 0
        while (result.size < limit) {
            val date = occurrence(index)
            if (date.isAfter(last)) break
            if (generatedThrough == null || date.isAfter(generatedThrough)) {
                result += date
            }
            index++

            // Pojistka proti nekonečné smyčce, kdyby někdo uložil nesmyslné pravidlo.
            if (index > MAX_SCAN) break
        }
        return result
    }

    private companion object {
        const val MAX_PER_RUN = 120
        const val MAX_SCAN = 100_000
    }
}
