package com.tripsplit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file format. This is the one part of the app that can lose data, so it
 * gets round-tripped here on the JVM with the real org.json.
 */
class StoreTest {

    private fun sampleLibrary(): Library {
        val people = listOf(Person("a1", "Ann"), Person("b2", "Bob"), Person("c3", "Cy"))
        val trip1 = Trip(
            id = "t1",
            createdAt = 1_700_000_000_000L,
            name = "Lakes",
            homeCurrency = "GBP",
            localCurrency = "EUR",
            rate = 0.86,
            people = people,
            expenses = listOf(
                Expense(
                    id = "e1", note = "Dinner", payerId = "a1",
                    sharedBy = listOf("a1", "b2", "c3"), homeMinor = 9000L,
                    createdAt = 1_700_000_100_000L, category = "Food"
                ),
                Expense(
                    id = "e2", note = "Boat", payerId = "b2",
                    sharedBy = listOf("a1", "b2"), homeMinor = 4300L,
                    localMinor = 5000L, rateUsed = 0.86, createdAt = 1_700_000_200_000L,
                    category = "Tickets", weights = mapOf("a1" to 3L, "b2" to 1L)
                ),
                Expense(
                    id = "e3", note = "", payerId = "c3",
                    sharedBy = listOf("c3"), homeMinor = 250L, createdAt = 0L
                )
            ),
            payments = listOf(
                Payment("p1", "b2", "a1", 1000L, "cash", createdAt = 1_700_000_300_000L),
                Payment("p2", "c3", "a1", 1234L, "", localMinor = 1435L, rateUsed = 0.86)
            ),
            started = true
        )
        val trip2 = Trip(id = "t2", createdAt = 1_600_000_000_000L, name = "Old", people = people, started = true)
        return Library(trips = listOf(trip1, trip2), activeId = "t1")
    }

    @Test fun roundTripsEverythingThroughJson() {
        val original = sampleLibrary()
        val text = Store.toJsonText(original)
        val back = Store.fromJsonText(text)
        assertNotNull(back)
        // Trip order inside the file doesn't matter; identity does.
        assertEquals(original.activeId, back!!.activeId)
        assertEquals(original.trips.sortedBy { it.id }, back.trips.sortedBy { it.id })
    }

    @Test fun weightsAndCategoriesSurvive() {
        val back = Store.fromJsonText(Store.toJsonText(sampleLibrary()))!!
        val boat = back.tripOf("t1")!!.expenses.first { it.id == "e2" }
        assertEquals(mapOf("a1" to 3L, "b2" to 1L), boat.weights)
        assertEquals("Tickets", boat.category)
        assertTrue(boat.isUneven)
        val dinner = back.tripOf("t1")!!.expenses.first { it.id == "e1" }
        assertNull(dinner.weights)
        assertEquals("Food", dinner.category)
    }

    @Test fun schemaThreeFilesWithoutTheNewFieldsStillLoad() {
        val text = """
            {"version":3,"activeId":"t9","trips":[{
              "id":"t9","createdAt":1700000000000,"name":"Legacy","homeCurrency":"USD",
              "localCurrency":"","rate":1.0,"started":true,
              "people":[{"id":"x","name":"Xa"},{"id":"y","name":"Yo"}],
              "expenses":[{"id":"e","note":"Taxi","payerId":"x","sharedBy":["x","y"],"homeMinor":2000,"createdAt":1700000001000}],
              "payments":[]
            }]}
        """.trimIndent()
        val lib = Store.fromJsonText(text)!!
        val e = lib.tripOf("t9")!!.expenses.single()
        assertEquals("", e.category)
        assertNull(e.weights)
        assertEquals(mapOf("x" to 1000L, "y" to 1000L), Settle.shares(e))
    }

    @Test fun aSingleTripFileFromSchemaOneBecomesALibrary() {
        val text = """
            {"version":1,"name":"First ever","homeCurrency":"EUR","localCurrency":"","rate":1.0,
             "people":[{"id":"p","name":"Pat"},{"id":"q","name":"Quin"}],
             "expenses":[{"id":"e","note":"Bus","payerId":"p","sharedBy":["p","q"],"homeMinor":800,"createdAt":1500000000000}],
             "started":true}
        """.trimIndent()
        val lib = Store.fromJsonText(text)!!
        assertEquals(1, lib.trips.size)
        val trip = lib.trips.single()
        assertEquals("First ever", trip.name)
        assertTrue(trip.id.isNotBlank())
        assertEquals(trip.id, lib.activeId)
        // No trip date in the old file: the earliest expense stands in.
        assertEquals(1500000000000L, trip.createdAt)
    }

    @Test fun garbageIsRejectedRatherThanImported() {
        assertNull(Store.fromJsonText("not json"))
        assertNull(Store.fromJsonText("{}"))
        assertNull(Store.fromJsonText("""{"version":4,"trips":[]}"""))
        assertNull(Store.fromJsonText("[1,2,3]"))
    }

    @Test fun activeIdThatPointsNowhereFallsBackToTheFirstTrip() {
        val text = Store.toJsonText(sampleLibrary()).replace("\"activeId\": \"t1\"", "\"activeId\": \"zz\"")
        val lib = Store.fromJsonText(text)!!
        assertTrue(lib.trips.any { it.id == lib.activeId })
    }

    @Test fun csvHasOneColumnPerPersonAndOneRowPerEntry() {
        val trip = sampleLibrary().tripOf("t1")!!
        val csv = Export.csv(trip)
        val lines = csv.trim().split("\r\n")
        assertEquals(1 + trip.expenses.size + trip.payments.size, lines.size)
        assertTrue(lines[0].endsWith("Share: Ann,Share: Bob,Share: Cy"))
        // A note with a comma is quoted so the columns stay aligned.
        val tricky = trip.copy(expenses = listOf(trip.expenses[0].copy(note = "Wine, lots")))
        val trickyCsv = Export.csv(tricky)
        assertTrue(trickyCsv.contains("\"Wine, lots\""))
        assertEquals(1 + 1 + trip.payments.size, trickyCsv.trim().split("\r\n").size)
        // Every data row has the same number of columns as the header.
        val header = lines[0].split(",").size
        lines.drop(1).forEach { assertEquals(header, it.split(",").size) }
    }

    @Test fun anEmptyObjectIsNotABackup() {
        assertNull(Store.fromJsonText("{}"))
        assertNull(Store.fromJsonText("""{"version":1,"name":"","people":[],"expenses":[]}"""))
    }
}
