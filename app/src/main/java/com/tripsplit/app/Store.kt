package com.tripsplit.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The whole trip lives in one small JSON file in the app's private storage.
 * No database, no network, nothing to sync — it works in airplane mode and
 * survives a reinstall only via Android's own backup, which is the honest
 * tradeoff for a single-device ledger.
 */
object Store {

    private const val FILE_NAME = "trip.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context): Trip {
        val f = file(ctx)
        if (!f.exists()) return Trip()
        return try {
            fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            // A corrupt file should never make the app unopenable; keep a copy
            // so nothing is silently destroyed, then start clean.
            try { f.copyTo(File(ctx.filesDir, "trip.corrupt.json"), overwrite = true) } catch (_: Exception) {}
            Trip()
        }
    }

    fun save(ctx: Context, trip: Trip) {
        try {
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(toJson(trip).toString())
            // write-then-rename, so a crash mid-save can't leave a half file
            tmp.renameTo(file(ctx))
        } catch (_: Exception) {
        }
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }

    private fun toJson(trip: Trip): JSONObject {
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
        return JSONObject()
            .put("version", 1)
            .put("name", trip.name)
            .put("homeCurrency", trip.homeCurrency)
            .put("localCurrency", trip.localCurrency)
            .put("rate", trip.rate)
            .put("started", trip.started)
            .put("people", people)
            .put("expenses", expenses)
    }

    private fun fromJson(o: JSONObject): Trip {
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

        return Trip(
            name = o.optString("name", ""),
            homeCurrency = o.optString("homeCurrency", "USD"),
            localCurrency = o.optString("localCurrency", ""),
            rate = o.optDouble("rate", 1.0),
            people = people,
            expenses = expenses,
            started = o.optBoolean("started", false)
        )
    }
}
