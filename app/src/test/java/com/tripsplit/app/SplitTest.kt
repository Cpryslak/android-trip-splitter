package com.tripsplit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Uneven splits: shares by weight and by exact amount. */
class WeightedShareTest {

    private val ids = listOf("a", "b", "c")

    @Test fun equalWeightsMatchTheEqualSplit() {
        val equal = Settle.shares(10000L, ids)
        val weighted = Settle.shares(10000L, ids, mapOf("a" to 1L, "b" to 1L, "c" to 1L))
        assertEquals(equal, weighted)
    }

    @Test fun twoSharesAgainstOneDoublesTheirPart() {
        val out = Settle.shares(10000L, ids, mapOf("a" to 2L, "b" to 1L, "c" to 1L))
        assertEquals(5000L, out["a"])
        assertEquals(2500L, out["b"])
        assertEquals(2500L, out["c"])
    }

    @Test fun exactCentsComeBackExactly() {
        val out = Settle.shares(10000L, ids, mapOf("a" to 4000L, "b" to 3500L, "c" to 2500L))
        assertEquals(4000L, out["a"])
        assertEquals(3500L, out["b"])
        assertEquals(2500L, out["c"])
    }

    @Test fun weightedPartsAlwaysResumToTheWhole() {
        val weights = mapOf("a" to 3L, "b" to 2L, "c" to 2L)
        for (total in listOf(1L, 2L, 7L, 99L, 100L, 101L, 12345L, 99999L)) {
            val out = Settle.shares(total, ids, weights)
            assertEquals("total $total", total, out.values.sum())
        }
    }

    @Test fun leftoverCentsGoToWhoeverWasRoundedDownMost() {
        // 100 split 3:2:2 -> exact 42.86 / 28.57 / 28.57 -> floors 42/28/28, two cents left.
        val out = Settle.shares(100L, ids, mapOf("a" to 3L, "b" to 2L, "c" to 2L))
        assertEquals(43L, out["a"])
        assertEquals(29L, out["b"])
        assertEquals(28L, out["c"])
        assertEquals(100L, out.values.sum())
    }

    @Test fun someoneWithNoSharesPaysNothing() {
        val out = Settle.shares(9999L, ids, mapOf("a" to 1L, "b" to 0L, "c" to 1L))
        assertEquals(0L, out["b"])
        assertEquals(9999L, out.values.sum())
        assertTrue((out["a"] ?: 0L) > 0L && (out["c"] ?: 0L) > 0L)
    }

    @Test fun missingWeightsCountAsOneShare() {
        val out = Settle.shares(300L, ids, mapOf("a" to 1L))
        assertEquals(100L, out["a"])
        assertEquals(100L, out["b"])
        assertEquals(100L, out["c"])
    }

    @Test fun allZeroWeightsFallBackToEqual() {
        val out = Settle.shares(300L, ids, mapOf("a" to 0L, "b" to 0L, "c" to 0L))
        assertEquals(100L, out["a"])
        assertEquals(300L, out.values.sum())
    }

    @Test fun anExpenseKnowsWhenItIsUneven() {
        val even = Expense("e1", "", "a", ids, 300L, weights = mapOf("a" to 2L, "b" to 2L, "c" to 2L))
        val uneven = even.copy(weights = mapOf("a" to 2L, "b" to 1L, "c" to 1L))
        val plain = even.copy(weights = null)
        assertFalse(even.isUneven)
        assertTrue(uneven.isUneven)
        assertFalse(plain.isUneven)
    }

    @Test fun balancesRespectWeights() {
        val trip = Trip(
            people = ids.map { Person(it, it) },
            expenses = listOf(
                Expense("e1", "dinner", "a", ids, 10000L, weights = mapOf("a" to 2L, "b" to 1L, "c" to 1L))
            ),
            started = true
        )
        val net = Settle.balances(trip).associate { it.personId to it.netMinor }
        assertEquals(5000L, net["a"])   // paid 100, own share 50
        assertEquals(-2500L, net["b"])
        assertEquals(-2500L, net["c"])
        val debts = Settle.pairDebts(trip)
        assertEquals(2, debts.size)
        assertTrue(debts.all { it.toId == "a" && it.amountMinor == 2500L })
    }

    @Test fun categoryTotalsPoolBlanksTogether() {
        val trip = Trip(
            people = ids.map { Person(it, it) },
            expenses = listOf(
                Expense("e1", "", "a", ids, 3000L, category = "Food"),
                Expense("e2", "", "a", ids, 1000L, category = "Food"),
                Expense("e3", "", "b", ids, 500L),
                Expense("e4", "", "b", ids, 9000L, category = "Lodging")
            ),
            started = true
        )
        val byCat = Settle.byCategory(trip)
        assertEquals(listOf("Lodging", "Food", Categories.UNCATEGORISED), byCat.map { it.first })
        assertEquals(listOf(9000L, 4000L, 500L), byCat.map { it.second })
        assertEquals(trip.totalMinor, byCat.sumOf { it.second })
    }
}

class RateFormatTest {

    @Test fun ratesNeverPrintInScientificNotation() {
        assertEquals("0.0001", Money.formatRate(1.0E-4))
        assertEquals("0.0107", Money.formatRate(0.0107))
        assertEquals("1", Money.formatRate(1.0))
        assertEquals("1.25", Money.formatRate(1.25))
        assertEquals("0.92", Money.formatRate(0.92))
    }

    @Test fun ratesParseWithEitherDecimalMark() {
        assertEquals(0.0107, Money.parseRate("0,0107")!!, 1e-12)
        assertEquals(0.0107, Money.parseRate("0.0107")!!, 1e-12)
        assertNull(Money.parseRate("abc"))
        assertNull(Money.parseRate("0"))
        assertNull(Money.parseRate("-2"))
    }

    @Test fun plainFormatHasNoGrouping() {
        assertEquals("1234.56", Money.plain(123456L))
        assertEquals("0.05", Money.plain(5L))
        assertEquals("-12.00", Money.plain(-1200L))
    }
}

class DatesTest {

    private val utc = ZoneOffset.UTC

    @Test fun movingToAnotherDayKeepsTheTimeOfDay() {
        // 2026-03-12 14:35 UTC
        val original = LocalDate.of(2026, 3, 12).atTime(14, 35).toInstant(utc).toEpochMilli()
        val moved = Dates.onDay(original, LocalDate.of(2026, 3, 10), utc)
        val expected = LocalDate.of(2026, 3, 10).atTime(14, 35).toInstant(utc).toEpochMilli()
        assertEquals(expected, moved)
        assertEquals(LocalDate.of(2026, 3, 10), Dates.dayOf(moved, utc))
    }

    @Test fun pickerMillisRoundTrip() {
        val day = LocalDate.of(2025, 12, 31)
        assertEquals(day, Dates.dayFromUtcMillis(Dates.utcMillisOf(day)))
    }

    @Test fun rangeLabelsAreAsShortAsTheyCanBe() {
        fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(utc).toInstant().toEpochMilli()
        val same = Dates.rangeLabel(at(2026, 3, 12), at(2026, 3, 12), utc)
        val sameMonth = Dates.rangeLabel(at(2026, 3, 12), at(2026, 3, 19), utc)
        val crossMonth = Dates.rangeLabel(at(2026, 2, 28), at(2026, 3, 3), utc)
        val crossYear = Dates.rangeLabel(at(2025, 12, 30), at(2026, 1, 2), utc)
        assertFalse(same.contains("–"))
        assertTrue(sameMonth.startsWith("12–19"))
        assertTrue(crossMonth.contains(" – "))
        assertTrue(crossYear.contains("2025") && crossYear.contains("2026"))
    }

    @Test fun todayAndYesterdayAreNamed() {
        val today = LocalDate.of(2026, 9, 10)
        assertEquals("Today", Dates.dayLabel(today, today))
        assertEquals("Yesterday", Dates.dayLabel(today.minusDays(1), today))
        assertFalse(Dates.dayLabel(today.minusDays(2), today).contains("2026"))
        assertTrue(Dates.dayLabel(today.minusYears(1), today).contains("2025"))
    }

    @Test fun tripDateRangeComesFromItsEntries() {
        val trip = Trip(
            createdAt = 5L,
            expenses = listOf(Expense("e1", "", "a", listOf("a"), 1L, createdAt = 300L)),
            payments = listOf(Payment("p1", "a", "b", 1L, createdAt = 900L))
        )
        assertEquals(300L, trip.firstEntryAt)
        assertEquals(900L, trip.lastEntryAt)
        assertNull(Trip().firstEntryAt)
    }
}
