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

    private fun payment(id: String, from: String, to: String, amount: Long) =
        Payment(
            id = id,
            fromId = from.lowercase(),
            toId = to.lowercase(),
            homeMinor = amount
        )

    @Test fun repaymentClearsOnePersonAndLeavesTheOthers() {
        // Chris books a 300.00 hotel for the three of them.
        val hotel = expense("hotel", "Chris", 30000L, listOf("Chris", "Mike", "Tanner"))
        val before = Trip(
            people = listOf("Chris", "Mike", "Tanner").map { person(it) },
            expenses = listOf(hotel),
            started = true
        )
        val owedEach = 10000L
        assertEquals(
            -owedEach,
            Settle.balances(before).first { it.personId == "tanner" }.netMinor
        )

        // Tanner hands Chris his hundred.
        val after = before.copy(payments = listOf(payment("p1", "Tanner", "Chris", owedEach)))
        val balances = Settle.balances(after)
        assertEquals(0L, balances.first { it.personId == "tanner" }.netMinor)
        assertEquals(-owedEach, balances.first { it.personId == "mike" }.netMinor)
        assertEquals(owedEach, balances.first { it.personId == "chris" }.netMinor)

        // Only Mike is left owing, and only to Chris.
        val moves = Settle.transfers(balances)
        assertEquals(1, moves.size)
        assertEquals("mike", moves[0].fromId)
        assertEquals("chris", moves[0].toId)
        assertEquals(owedEach, moves[0].amountMinor)
    }

    @Test fun payingEverythingLeavesNothingToSettle() {
        val t = Trip(
            people = listOf("A", "B").map { person(it) },
            expenses = listOf(expense("x", "A", 5000L, listOf("A", "B"))),
            payments = listOf(payment("p", "B", "A", 2500L)),
            started = true
        )
        assertTrue(Settle.transfers(Settle.balances(t)).isEmpty())
    }

    @Test fun aRepaymentIsNotATripCost() {
        val t = Trip(
            people = listOf("A", "B").map { person(it) },
            expenses = listOf(expense("x", "A", 5000L, listOf("A", "B"))),
            payments = listOf(payment("p", "B", "A", 2500L)),
            started = true
        )
        assertEquals(5000L, t.totalMinor)
        assertEquals(2500L, t.settledMinor)
    }

    @Test fun nobodyIsEverToldToPayThemselves() {
        val names = listOf("Chris", "Mike", "Tanner", "Jake", "Pat", "Sam")
        val r = Random(21)
        repeat(3000) {
            val group = names.shuffled(r).take(3 + r.nextInt(4))
            val expenses = (0 until 1 + r.nextInt(12)).map { i ->
                expense(
                    "e$i",
                    group[r.nextInt(group.size)],
                    1L + r.nextLong(500_000L),
                    group.shuffled(r).take(1 + r.nextInt(group.size))
                )
            }
            val payments = (0 until r.nextInt(7)).map { i ->
                payment(
                    "p$i",
                    group[r.nextInt(group.size)],
                    group[r.nextInt(group.size)],
                    1L + r.nextLong(200_000L)
                )
            }
            val t = Trip(
                people = group.map { person(it) },
                expenses = expenses,
                payments = payments,
                started = true
            )
            val balances = Settle.balances(t)
            assertEquals(0L, balances.sumOf { it.netMinor })

            val moves = Settle.transfers(balances)
            assertTrue("told someone to pay themselves", moves.all { it.fromId != it.toId })
            assertTrue(moves.all { it.amountMinor > 0L })
            assertTrue(moves.size <= group.size - 1)

            val after = HashMap<String, Long>()
            balances.forEach { after[it.personId] = it.netMinor }
            moves.forEach { m ->
                after[m.fromId] = (after[m.fromId] ?: 0L) + m.amountMinor
                after[m.toId] = (after[m.toId] ?: 0L) - m.amountMinor
            }
            assertTrue("balances not fully settled", after.values.all { it == 0L })
        }
    }

    @Test fun aPaymentToYourselfIsIgnoredRatherThanDoubleCounted() {
        val t = Trip(
            people = listOf("A", "B").map { person(it) },
            expenses = listOf(expense("x", "A", 1000L, listOf("A", "B"))),
            payments = listOf(payment("p", "A", "A", 9999L)),
            started = true
        )
        val a = Settle.balances(t).first { it.personId == "a" }
        assertEquals(0L, a.sentMinor)
        assertEquals(0L, a.receivedMinor)
        assertEquals(500L, a.netMinor)
    }

    @Test fun conversionFreezesAtEntryTime() {
        // 45.00 local at 1.08 -> 48.60 home
        assertEquals(4860L, Money.convert(4500L, 1.08))
        assertEquals(1L, Money.convert(1L, 1.4))
    }
}

class LibraryTest {

    private fun trip(id: String, name: String, created: Long, expenses: List<Expense> = emptyList()) =
        Trip(
            id = id,
            createdAt = created,
            name = name,
            people = listOf(Person("a", "A"), Person("b", "B")),
            expenses = expenses,
            started = true
        )

    private fun spend(id: String, amount: Long) =
        Expense(
            id = id,
            note = id,
            payerId = "a",
            sharedBy = listOf("a", "b"),
            homeMinor = amount
        )

    @Test fun opensTheNewestTripWhenTheActiveIdIsMissing() {
        val lib = Library(
            trips = listOf(trip("old", "Last year", 1_000L), trip("new", "This year", 9_000L)),
            activeId = "vanished"
        )
        assertEquals("new", lib.active?.id)
    }

    @Test fun startingANewTripLeavesTheOldOneExactlyAsItWas() {
        val last = trip("t1", "Rome", 1_000L, listOf(spend("e1", 5000L)))
        var lib = Library(trips = listOf(last), activeId = "t1")
        lib = lib.withTrip(trip("t2", "Lisbon", 2_000L))

        assertEquals(2, lib.trips.size)
        assertEquals("t2", lib.activeId)
        val reopened = lib.trips.first { it.id == "t1" }
        assertEquals(5000L, reopened.totalMinor)
        assertEquals(1, reopened.expenses.size)
        assertEquals(0L, lib.trips.first { it.id == "t2" }.totalMinor)
    }

    @Test fun editingOneTripCannotTouchAnother() {
        val a = trip("t1", "Rome", 1_000L, listOf(spend("e1", 5000L)))
        val b = trip("t2", "Lisbon", 2_000L, listOf(spend("e2", 700L)))
        var lib = Library(trips = listOf(a, b), activeId = "t1")

        val editedA = a.copy(expenses = a.expenses + spend("e9", 100L))
        lib = lib.withTrip(editedA)

        assertEquals(5100L, lib.trips.first { it.id == "t1" }.totalMinor)
        assertEquals(700L, lib.trips.first { it.id == "t2" }.totalMinor)
    }

    @Test fun deletingATripKeepsTheRestAndRepointsActive() {
        val lib = Library(
            trips = listOf(trip("t1", "Rome", 1_000L), trip("t2", "Lisbon", 2_000L)),
            activeId = "t2"
        ).without("t2")

        assertEquals(1, lib.trips.size)
        assertEquals("t1", lib.activeId)
    }

    @Test fun deletingTheOnlyTripLeavesAnEmptyLibrary() {
        val lib = Library(trips = listOf(trip("t1", "Rome", 1_000L)), activeId = "t1").without("t1")
        assertTrue(lib.trips.isEmpty())
        assertNull(lib.active)
    }

    @Test fun restoringNeverDestroysATripMissingFromTheBackup() {
        val onTablet = Library(
            trips = listOf(
                trip("t1", "Rome", 1_000L, listOf(spend("e1", 5000L))),
                trip("t2", "Lisbon", 2_000L, listOf(spend("e2", 700L)))
            ),
            activeId = "t2"
        )
        // A backup taken before Lisbon existed, with Rome since edited.
        val backup = Library(
            trips = listOf(trip("t1", "Rome", 1_000L, listOf(spend("e1", 9999L))), trip("t3", "Oslo", 500L)),
            activeId = "t1"
        )

        val (merged, counts) = onTablet.merge(backup)
        val (added, replaced) = counts

        assertEquals(1, added)       // Oslo
        assertEquals(1, replaced)    // Rome
        assertEquals(3, merged.trips.size)
        // Lisbon was not in the backup and survives untouched
        assertEquals(700L, merged.trips.first { it.id == "t2" }.totalMinor)
        // Rome came from the file
        assertEquals(9999L, merged.trips.first { it.id == "t1" }.totalMinor)
    }

    @Test fun restoringIntoAnEmptyLibraryAddsEverything() {
        val backup = Library(
            trips = listOf(trip("t1", "Rome", 1_000L), trip("t2", "Lisbon", 2_000L)),
            activeId = "t1"
        )
        val (merged, counts) = Library().merge(backup)
        assertEquals(2, counts.first)
        assertEquals(0, counts.second)
        assertEquals(2, merged.trips.size)
        assertEquals("t1", merged.activeId)
    }

    @Test fun outstandingCountReflectsRepayments() {
        val t = trip("t1", "Rome", 1_000L, listOf(spend("e1", 5000L)))
        assertEquals(1, t.outstandingCount)
        val settled = t.copy(
            payments = listOf(Payment(id = "p1", fromId = "b", toId = "a", homeMinor = 2500L))
        )
        assertEquals(0, settled.outstandingCount)
    }
}
