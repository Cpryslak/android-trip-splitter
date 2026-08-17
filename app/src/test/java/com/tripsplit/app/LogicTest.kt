package com.tripsplit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MoneyTest {

    @Test fun parsesPlainAndGroupedAmounts() {
        assertEquals(1234L, Money.parse("12.34"))
        assertEquals(1234L, Money.parse("12,34"))
        assertEquals(123456L, Money.parse("1,234.56"))
        assertEquals(123456L, Money.parse("1 234,56"))
        assertEquals(1200L, Money.parse("12"))
        assertEquals(1250L, Money.parse("12.5"))
        assertEquals(50L, Money.parse(".5"))
        assertEquals(150000L, Money.parse("1,500"))
    }

    @Test fun rejectsNonNumbers() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("dinner"))
    }

    @Test fun formatsWithGroupingAndTwoPlaces() {
        assertEquals("0.00", Money.format(0L))
        assertEquals("0.07", Money.format(7L))
        assertEquals("12.34", Money.format(1234L))
        assertEquals("1,234.56", Money.format(123456L))
        assertEquals("1,000,000.00", Money.format(100000000L))
        assertEquals("-4.50", Money.format(-450L))
    }

    @Test fun roundTripsThroughParseAndFormat() {
        val r = Random(11)
        repeat(2000) {
            val minor = r.nextLong(0, 90_000_000L)
            assertEquals(minor, Money.parse(Money.format(minor)))
        }
    }
}

class SettleTest {

    private fun person(n: String) = Person(n.lowercase(), n)

    private fun trip(people: List<String>, expenses: List<Expense>) =
        Trip(
            name = "Test",
            people = people.map { person(it) },
            expenses = expenses,
            started = true
        )

    private fun expense(id: String, payer: String, amount: Long, sharedBy: List<String>) =
        Expense(
            id = id,
            note = id,
            payerId = payer.lowercase(),
            sharedBy = sharedBy.map { it.lowercase() },
            homeMinor = amount
        )

    @Test fun sharesNeverLoseOrInventCents() {
        val ids = listOf("a", "b", "c")
        val parts = Settle.shares(100L, ids)
        assertEquals(100L, parts.values.sum())
        assertEquals(listOf(33L, 33L, 34L), parts.values.sorted())
    }

    @Test fun sharesHandleOneCentAndOnePerson() {
        assertEquals(1L, Settle.shares(1L, listOf("a", "b", "c")).values.sum())
        assertEquals(mapOf("a" to 500L), Settle.shares(500L, listOf("a")))
        assertTrue(Settle.shares(500L, emptyList()).isEmpty())
    }

    @Test fun balancesAlwaysSumToZero() {
        val t = trip(
            listOf("Dad", "Mom", "Rachel", "Ben"),
            listOf(
                expense("dinner", "Dad", 24000L, listOf("Dad", "Mom", "Rachel", "Ben")),
                expense("taxi", "Mom", 8550L, listOf("Dad", "Mom", "Rachel", "Ben")),
                expense("museum", "Rachel", 10000L, listOf("Rachel", "Ben")),
                expense("odd", "Ben", 1L, listOf("Dad", "Mom", "Rachel", "Ben"))
            )
        )
        assertEquals(0L, Settle.balances(t).sumOf { it.netMinor })
        assertEquals(42551L, t.totalMinor)
    }

    @Test fun transfersClearEveryBalanceExactly() {
        val names = listOf("Aunt Pat", "Ben", "Dad", "Mom", "Rachel", "Uncle Joe")
        val r = Random(7)
        repeat(3000) {
            val group = names.shuffled(r).take(3 + r.nextInt(4))
            val expenses = (0 until 1 + r.nextInt(15)).map { i ->
                val sharers = group.shuffled(r).take(1 + r.nextInt(group.size))
                expense("e$i", group[r.nextInt(group.size)], 1L + r.nextLong(500_000L), sharers)
            }
            val t = trip(group, expenses)
            val balances = Settle.balances(t)
            assertEquals(0L, balances.sumOf { it.netMinor })

            val moves = Settle.transfers(balances)
            assertTrue("more transfers than people - 1", moves.size <= group.size - 1)
            assertTrue("a transfer was zero or negative", moves.all { it.amountMinor > 0L })

            val after = HashMap<String, Long>()
            balances.forEach { after[it.personId] = it.netMinor }
            moves.forEach { m ->
                after[m.fromId] = (after[m.fromId] ?: 0L) + m.amountMinor
                after[m.toId] = (after[m.toId] ?: 0L) - m.amountMinor
            }
            assertTrue("balances not fully settled", after.values.all { it == 0L })
        }
    }

    @Test fun nobodyOwesAnythingWhenAllSquare() {
        val t = trip(
            listOf("A", "B"),
            listOf(
                expense("x", "A", 1000L, listOf("A", "B")),
                expense("y", "B", 1000L, listOf("A", "B"))
            )
        )
        assertTrue(Settle.transfers(Settle.balances(t)).isEmpty())
    }

    @Test fun personalExpenseCostsOnlyThatPerson() {
        val t = trip(
            listOf("A", "B"),
            listOf(expense("solo", "A", 5000L, listOf("A")))
        )
        assertTrue(Settle.transfers(Settle.balances(t)).isEmpty())
    }

    @Test fun conversionFreezesAtEntryTime() {
        // 45.00 local at 1.08 -> 48.60 home
        assertEquals(4860L, Money.convert(4500L, 1.08))
        assertEquals(1L, Money.convert(1L, 1.4))
    }
}
