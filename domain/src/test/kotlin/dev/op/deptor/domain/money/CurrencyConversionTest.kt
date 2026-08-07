package dev.op.deptor.domain.money

import dev.op.deptor.domain.TestData.anna
import dev.op.deptor.domain.TestData.bob
import dev.op.deptor.domain.TestData.cyril
import dev.op.deptor.domain.balance.computeBalances
import dev.op.deptor.domain.balance.computePairwiseDebts
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Payer
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionId
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.split.ExpenseSplitter
import java.math.BigDecimal
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CurrencyConversionTest {

    private val czk = CurrencyCode.CZK
    private val eur = CurrencyCode.EUR
    private val jpy = CurrencyCode("JPY")
    private val everyone = listOf(anna, bob, cyril)

    private fun rate(value: String) = ExchangeRate(BigDecimal(value))

    private fun foreignExpense(
        amount: Long,
        currency: CurrencyCode,
        rate: ExchangeRate?,
        between: List<MemberId> = everyone,
        payer: MemberId = anna,
    ) = Transaction(
        id = TransactionId("t1"),
        groupId = GroupId("g1"),
        type = TransactionType.EXPENSE,
        amount = Money(amount),
        currency = currency,
        date = Instant.EPOCH,
        description = "výdaj v cizině",
        payers = listOf(Payer(payer, Money(amount))),
        splits = ExpenseSplitter.equally(Money(amount), between),
        exchangeRate = rate,
    )

    // --- samotný kurz ---

    @Test
    fun `kurz prevede castku`() {
        // 100 EUR při kurzu 25 = 2500 CZK
        assertEquals(Money(250_000), rate("25").convert(Money(10_000), eur, czk))
    }

    @Test
    fun `kurz jedna nic nemeni`() {
        assertEquals(Money(12_345), ExchangeRate.IDENTITY.convert(Money(12_345), czk, czk))
    }

    @Test
    fun `prevod mezi menami s ruznym poctem desetinnych mist`() {
        // JPY nemá desetinná místa: 1000 JPY při kurzu 0,15 = 150 CZK
        assertEquals(Money(15_000), rate("0.15").convert(Money(1_000), jpy, czk))
        // A zpět: 150 CZK při kurzu 6,6667 ≈ 1000 JPY
        assertEquals(Money(1_000), rate("6.6667").convert(Money(15_000), czk, jpy))
    }

    @Test
    fun `zaporny a nulovy kurz je chyba`() {
        assertFailsWith<IllegalArgumentException> { ExchangeRate(BigDecimal("-1")) }
        assertFailsWith<IllegalArgumentException> { ExchangeRate(BigDecimal.ZERO) }
    }

    @Test
    fun `kurz se da precist z textu s carkou i teckou`() {
        assertEquals(rate("24.5"), ExchangeRate.parse("24,5").getOrThrow())
        assertEquals(rate("24.5"), ExchangeRate.parse("24.5").getOrThrow())
        assertTrue(ExchangeRate.parse("nesmysl").isFailure)
    }

    // --- převod transakce ---

    @Test
    fun `prevedena transakce ma sedici soucty`() {
        // 100 EUR mezi tři při kurzu 25,37 — nic z toho nevychází hezky.
        val converted = foreignExpense(10_000, eur, rate("25.37")).convertedTo(czk)

        assertEquals(czk, converted.currency)
        assertEquals(Money(253_700), converted.amount)
        assertEquals(253_700L, converted.payers.sumOf { it.amount.minor })
        assertEquals(253_700L, converted.splits.sumOf { it.amount.minor })
    }

    @Test
    fun `prevedena transakce je porad platna`() {
        // Konstruktor Transaction invariant vynucuje, takže tohle by spadlo,
        // kdyby se převod udělal po částech místo z celku.
        val converted = foreignExpense(9_999, eur, rate("25.37")).convertedTo(czk)
        assertEquals(converted.amount.minor, converted.splits.sumOf { it.amount.minor })
    }

    @Test
    fun `prevod zachova pomer podilu`() {
        val converted = foreignExpense(
            amount = 9_000,
            currency = eur,
            rate = rate("25"),
            between = listOf(anna, bob),
        ).convertedTo(czk)

        // Půl na půl zůstane půl na půl.
        assertEquals(converted.splits[0].amount, converted.splits[1].amount)
    }

    @Test
    fun `drobna castka se neztrati uplne`() {
        // 0,01 EUR by při převodu na jen dělitelnou měnu spadlo na nulu a transakce
        // by přestala být platná. Radši být o jednotku vedle než záznam ztratit.
        val converted = foreignExpense(1, czk, rate("0.001"), between = listOf(anna))
            .convertedTo(eur)

        assertTrue(converted.amount.isPositive)
        assertEquals(converted.amount.minor, converted.splits.sumOf { it.amount.minor })
    }

    @Test
    fun `transakce ve stejne mene se nemeni`() {
        val original = foreignExpense(9_000, czk, null)
        assertTrue(original === original.convertedTo(czk))
    }

    @Test
    fun `prevod je idempotentni`() {
        val once = foreignExpense(10_000, eur, rate("25.37")).convertedTo(czk)
        assertEquals(once, once.convertedTo(czk))
    }

    // --- salda nad více měnami ---

    @Test
    fun `saldo secte koruny i eura`() {
        val balances = computeBalances(
            listOf(
                foreignExpense(90_000, czk, null, payer = anna),
                foreignExpense(10_000, eur, rate("25"), payer = bob),
            ),
            everyone,
            czk,
        ).associate { it.memberId to it.amount.minor }

        // Anna vložila 900 Kč a spotřebovala 300 Kč z prvního výdaje plus 833,50 Kč
        // z druhého (100 € × 25 rozděleno na 833,50 / 833,25 / 833,25).
        assertEquals(0L, balances.values.sum())
        assertEquals(90_000L - 30_000L - 83_350L, balances.getValue(anna))
    }

    @Test
    fun `soucet sald je nula i pri michani men`() {
        val random = Random(7)
        repeat(300) {
            val transactions = List(random.nextInt(1, 5)) {
                val foreign = random.nextBoolean()
                foreignExpense(
                    amount = random.nextLong(1, 500_000),
                    currency = if (foreign) eur else czk,
                    rate = if (foreign) rate("2${random.nextInt(0, 9)}.${random.nextInt(10, 99)}") else null,
                    between = everyone.filter { random.nextBoolean() }.ifEmpty { everyone },
                    payer = everyone[random.nextInt(everyone.size)],
                )
            }

            val balances = computeBalances(transactions, everyone, czk)
            assertEquals(
                0L,
                balances.sumOf { it.amount.minor },
                "salda se nesečetla na nulu u $transactions",
            )
        }
    }

    @Test
    fun `parove dluhy sedi se saldy i pri michani men`() {
        val transactions = listOf(
            foreignExpense(90_000, czk, null, payer = anna),
            foreignExpense(10_000, eur, rate("25.37"), payer = bob),
        )

        val balances = computeBalances(transactions, everyone, czk)
            .associate { it.memberId to it.amount.minor }
        val pairwise = computePairwiseDebts(transactions, czk)

        everyone.forEach { member ->
            val net = pairwise.filter { it.to == member }.sumOf { it.amount.minor } -
                pairwise.filter { it.from == member }.sumOf { it.amount.minor }
            assertEquals(balances.getValue(member), net, "rozešlo se pro $member")
        }
    }

    @Test
    fun `zmena kurzu nezmeni uz zadanou transakci`() {
        // Podstata rozhodnutí držet kurz u transakce: dvě stejné částky zadané
        // při různých kurzech zůstanou různé, protože každá si nese svůj.
        val vcera = foreignExpense(10_000, eur, rate("25.00")).convertedTo(czk)
        val dnes = foreignExpense(10_000, eur, rate("26.00")).convertedTo(czk)

        assertEquals(Money(250_000), vcera.amount)
        assertEquals(Money(260_000), dnes.amount)
    }
}
