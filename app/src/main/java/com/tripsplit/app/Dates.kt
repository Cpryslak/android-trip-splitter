package com.tripsplit.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Calendar helpers. Timestamps are epoch millis everywhere in the model; this is
 * the one place that turns them into days a person would recognise.
 */
object Dates {

    fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** "Today", "Yesterday", "Tue 12 Mar", or with the year when it isn't this one. */
    fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> {
            val pattern = if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy"
            day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }

    /** "12 Mar 2026" */
    fun shortDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (millis <= 0L) ""
        else dayOf(millis, zone).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))

    /**
     * "12–19 Mar 2026", "28 Feb – 3 Mar 2026", or "30 Dec 2025 – 2 Jan 2026":
     * as short as the two ends allow.
     */
    fun rangeLabel(fromMillis: Long, toMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val a = dayOf(minOf(fromMillis, toMillis), zone)
        val b = dayOf(maxOf(fromMillis, toMillis), zone)
        val locale = Locale.getDefault()
        val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        if (a == b) return a.format(full)
        if (a.year != b.year) return a.format(full) + " – " + b.format(full)
        if (a.month != b.month) {
            return a.format(DateTimeFormatter.ofPattern("d MMM", locale)) + " – " + b.format(full)
        }
        return a.dayOfMonth.toString() + "–" + b.format(full)
    }

    /** Moves a timestamp onto another calendar day, keeping its time of day. */
    fun onDay(millis: Long, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
        val time: LocalTime = if (millis > 0L)
            Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        else LocalTime.NOON
        return day.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** The Material date picker speaks UTC midnight; these translate both ways. */
    fun dayFromUtcMillis(utcMillis: Long): LocalDate =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    fun utcMillisOf(day: LocalDate): Long =
        day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** For spreadsheets: "2026-09-10" and "14:32". */
    fun isoDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (millis <= 0L) "" else dayOf(millis, zone).toString()

    fun clockTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (millis <= 0L) ""
        else Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

    /** For file names: "2026-09-10-1432". */
    fun fileStamp(millis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US))

    /** For status lines: "10 Sep, 14:32". */
    fun timeLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (millis <= 0L) ""
        else Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))
}
