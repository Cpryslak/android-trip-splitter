package com.tripsplit.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Every trip lives in one small JSON file in the app's private storage. No
 * database, no network, nothing to sync — it works in airplane mode.
 *
 * Schema 3 holds a library of trips. Files written by schema 1 and 2 held a
 * single trip and still load: they become the first trip in the library.
 */
object Store {

    private const val FILE_NAME = "trips.json"
    private const val LEGACY_FILE_NAME = "trip.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context): Library {
        val current = file(ctx)
        val legacy = File(ctx.filesDir, LEGACY_FILE_NAME)
        val source = if (current.exists()) current else legacy
        if (!source.exists()) return Library()

        return try {
            val library = libraryFromJson(JSONObject(source.readText()))
            // Snapshot the last state that definitely parsed. If a future bug
            // ever writes nonsense, the previous session is still sitting here.
            try {
                source.copyTo(File(ctx.filesDir, "trips.lastgood.json"), overwrite = true)
            } catch (_: Exception) {}
            // Migrating forward: write the new shape, leave the old file alone
            // rather than deleting the only copy of anything.
            if (source === legacy) save(ctx, library)
            library
        } catch (e: Exception) {
            try {
                source.copyTo(File(ctx.filesDir, "trips.corrupt.json"), overwrite = true)
            } catch (_: Exception) {}
            Library()
        }
    }

    fun save(ctx: Context, library: Library) {
        try {
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(libraryToJson(library).toString())
            // write-then-rename, so a crash mid-save can't leave a half file
            tmp.renameTo(file(ctx))
        } catch (_: Exception) {
        }
    }

    /** Pretty JSON, for a backup a human can actually read. */
    fun toJsonText(library: Library): String = libraryToJson(library).toString(2)

    /** Null when the text isn't a trip file, so a bad import can't wipe anything. */
    fun fromJsonText(text: String): Library? = try {
        val library = libraryFromJson(JSONObject(text))
        if (library.trips.isEmpty()) null else library
    } catch (e: Exception) {
        null
    }

    /* ------------------------------------------------------------- writing */

    private fun libraryToJson(library: Library): JSONObject {
        val trips = JSONArray()
        library.trips.forEach { trips.put(tripToJson(it)) }
        return JSONObject()
            .put("version", 3)
            .put("activeId", library.activeId)
            .put("trips", trips)
    }

    private fun tripToJson(trip: Trip): JSONObject {
        val people = JSONArray()
        trip.people.forEach { p ->
            people.put(JSONObject().put("id", p.id).put("name", p.name))
        }
        val expenses = JSONArray()
        trip.expenses.forEach { e ->
            val shared = JSONArray()
            e.sharedBy.forEach { shared.put(it) }
            val o = JSONObject()
                .put("id", e.id)
                .put("note", e.note)
                .put("payerId", e.payerId)
                .put("sharedBy", shared)
                .put("homeMinor", e.homeMinor)
                .put("createdAt", e.createdAt)
            if (e.localMinor != null) o.put("localMinor", e.localMinor)
            if (e.rateUsed != null) o.put("rateUsed", e.rateUsed)
            expenses.put(o)
        }
        val payments = JSONArray()
        trip.payments.forEach { p ->
            val o = JSONObject()
                .put("id", p.id)
                .put("fromId", p.fromId)
                .put("toId", p.toId)
                .put("homeMinor", p.homeMinor)
                .put("note", p.note)
                .put("createdAt", p.createdAt)
            if (p.localMinor != null) o.put("localMinor", p.localMinor)
            if (p.rateUsed != null) o.put("rateUsed", p.rateUsed)
            payments.put(o)
        }
        return JSONObject()
            .put("id", trip.id)
            .put("createdAt", trip.createdAt)
            .put("name", trip.name)
            .put("homeCurrency", trip.homeCurrency)
            .put("localCurrency", trip.localCurrency)
            .put("rate", trip.rate)
            .put("started", trip.started)
            .put("people", people)
            .put("expenses", expenses)
            .put("payments", payments)
    }

    /* ------------------------------------------------------------- reading */

    private fun libraryFromJson(o: JSONObject): Library {
        // Schema 1 and 2 files are a bare trip with no "trips" array.
        val tripsArr = o.optJSONArray("trips")
            ?: return singleTripLibrary(tripFromJson(o))

        val trips = ArrayList<Trip>()
        for (i in 0 until tripsArr.length()) {
            trips.add(tripFromJson(tripsArr.getJSONObject(i)))
        }
        val active = o.optString("activeId", "")
        return Library(
            trips = trips,
            activeId = if (trips.any { it.id == active }) active else trips.firstOrNull()?.id ?: ""
        )
    }

    private fun singleTripLibrary(trip: Trip): Library =
        Library(trips = listOf(trip), activeId = trip.id)

    private fun tripFromJson(o: JSONObject): Trip {
        val people = ArrayList<Person>()
        val pArr = o.optJSONArray("people") ?: JSONArray()
        for (i in 0 until pArr.length()) {
            val p = pArr.getJSONObject(i)
            people.add(Person(p.getString("id"), p.optString("name", "")))
        }

        val expenses = ArrayList<Expense>()
        val eArr = o.optJSONArray("expenses") ?: JSONArray()
        for (i in 0 until eArr.length()) {
            val e = eArr.getJSONObject(i)
            val sharedArr = e.optJSONArray("sharedBy") ?: JSONArray()
            val shared = ArrayList<String>()
            for (j in 0 until sharedArr.length()) shared.add(sharedArr.getString(j))
            expenses.add(
                Expense(
                    id = e.getString("id"),
                    note = e.optString("note", ""),
                    payerId = e.optString("payerId", ""),
                    sharedBy = shared,
                    homeMinor = e.optLong("homeMinor", 0L),
                    localMinor = if (e.has("localMinor")) e.optLong("localMinor") else null,
                    rateUsed = if (e.has("rateUsed")) e.optDouble("rateUsed") else null,
                    createdAt = e.optLong("createdAt", 0L)
                )
            )
        }

        val payments = ArrayList<Payment>()
        val payArr = o.optJSONArray("payments") ?: JSONArray()
        for (i in 0 until payArr.length()) {
            val p = payArr.getJSONObject(i)
            payments.add(
                Payment(
                    id = p.getString("id"),
                    fromId = p.optString("fromId", ""),
                    toId = p.optString("toId", ""),
                    homeMinor = p.optLong("homeMinor", 0L),
                    note = p.optString("note", ""),
                    localMinor = if (p.has("localMinor")) p.optLong("localMinor") else null,
                    rateUsed = if (p.has("rateUsed")) p.optDouble("rateUsed") else null,
                    createdAt = p.optLong("createdAt", 0L)
                )
            )
        }

        // Trips predating the library had no id or date. Invent them once, then
        // they're stable forever.
        val id = o.optString("id", "").ifBlank { UUID.randomUUID().toString().take(8) }
        val created = o.optLong("createdAt", 0L).let {
            if (it > 0L) it else expenses.minOfOrNull { e -> e.createdAt }
                ?.takeIf { min -> min > 0L } ?: System.currentTimeMillis()
        }

        return Trip(
            id = id,
            createdAt = created,
            name = o.optString("name", ""),
            homeCurrency = o.optString("homeCurrency", "USD"),
            localCurrency = o.optString("localCurrency", ""),
            rate = o.optDouble("rate", 1.0),
            people = people,
            expenses = expenses,
            payments = payments,
            started = o.optBoolean("started", false)
        )
    }
}
