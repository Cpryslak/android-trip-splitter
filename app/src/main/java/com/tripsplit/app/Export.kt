package com.tripsplit.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * The ledger as a spreadsheet, for anyone who wants to check the numbers their
 * own way. One row per entry, one column per person holding their share, so a
 * SUM down each column reproduces the balances.
 */
object Export {

    private const val AUTHORITY = "com.tripsplit.app.files"

    /** Byte-order mark, so Excel reads accented names as UTF-8 instead of guessing. */
    private const val BOM = "\uFEFF"

    fun csv(trip: Trip): String {
        val sb = StringBuilder()
        sb.append(BOM)
        val people = trip.people
        val header = listOf(
            "Date", "Time", "Type", "Description", "Category", "Paid by", "Received by",
            "Amount (" + trip.homeCurrency + ")", "Local amount", "Local currency", "Rate",
            "Split between"
        ) + people.map { "Share: " + it.name }
        sb.append(header.joinToString(",") { field(it) }).append("\r\n")

        data class Line(val at: Long, val cells: List<String>)
        val lines = ArrayList<Line>()

        for (e in trip.expenses) {
            val shares = Settle.shares(e)
            val cells = listOf(
                Dates.isoDate(e.createdAt),
                Dates.clockTime(e.createdAt),
                "Expense",
                e.note,
                e.category,
                trip.nameOf(e.payerId),
                "",
                Money.plain(e.homeMinor),
                e.localMinor?.let { Money.plain(it) } ?: "",
                if (e.localMinor != null) trip.localCurrency else "",
                e.rateUsed?.let { Money.formatRate(it) } ?: "",
                e.sharedBy.joinToString("; ") { trip.nameOf(it) } +
                    (if (e.isUneven) " (uneven)" else "")
            ) + people.map { p -> shares[p.id]?.let { Money.plain(it) } ?: "" }
            lines.add(Line(e.createdAt, cells))
        }
        for (p in trip.payments) {
            val cells = listOf(
                Dates.isoDate(p.createdAt),
                Dates.clockTime(p.createdAt),
                "Repayment",
                p.note,
                "",
                trip.nameOf(p.fromId),
                trip.nameOf(p.toId),
                Money.plain(p.homeMinor),
                p.localMinor?.let { Money.plain(it) } ?: "",
                if (p.localMinor != null) trip.localCurrency else "",
                p.rateUsed?.let { Money.formatRate(it) } ?: "",
                ""
            ) + people.map { "" }
            lines.add(Line(p.createdAt, cells))
        }

        lines.sortedBy { it.at }.forEach { line ->
            sb.append(line.cells.joinToString(",") { field(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** Quotes anything a spreadsheet could misread. */
    private fun field(raw: String): String {
        val needsQuotes = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + raw.replace("\"", "\"\"") + "\"" else raw
    }

    fun fileName(trip: Trip): String {
        val base = trip.name.ifBlank { "trip" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "trip" }
        return base + "-ledger-" + Dates.fileStamp() + ".csv"
    }

    fun shareIntent(ctx: Context, trip: Trip): Intent {
        val dir = File(ctx.cacheDir, "exports")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(trip))
        file.writeText(csv(trip))
        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, (trip.name.ifBlank { "Trip" }) + " ledger")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "The ledger for " + trip.name.ifBlank { "the trip" } +
                        " as a spreadsheet. One column per person holds their share of each expense."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Send ledger as CSV"
        )
    }
}
