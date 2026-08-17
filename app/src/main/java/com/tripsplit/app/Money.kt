package com.tripsplit.app

/**
 * Everything is integer minor units (cents). Floating point is never allowed
 * near a running total — 0.1 + 0.2 problems become real money problems.
 */
object Money {

    /**
     * Parses what a person actually types: "12.34", "12,34", "1,234.5", "1 234,56".
     * Returns null when there's no number in there at all.
     */
    fun parse(input: String): Long? {
        val cleaned = input.trim().replace(" ", "").replace("\u00A0", "")
        if (cleaned.isEmpty()) return null

        val sepIndex = maxOf(cleaned.lastIndexOf('.'), cleaned.lastIndexOf(','))
        val trailing = cleaned.length - sepIndex - 1

        val wholePart: String
        var fracPart = ""
        if (sepIndex >= 0 && trailing in 1..2) {
            wholePart = cleaned.substring(0, sepIndex)
            fracPart = cleaned.substring(sepIndex + 1)
        } else {
            wholePart = cleaned
        }

        val wholeDigits = wholePart.filter { it.isDigit() }
        val fracDigits = fracPart.filter { it.isDigit() }
        if (wholeDigits.isEmpty() && fracDigits.isEmpty()) return null

        val major = if (wholeDigits.isEmpty()) 0L else wholeDigits.toLongOrNull() ?: return null
        val cents = (fracDigits + "00").take(2).toLong()
        return major * 100L + cents
    }

    /** 123456 -> "1,234.56" */
    fun format(minor: Long): String {
        val negative = minor < 0
        val abs = if (negative) -minor else minor
        val grouped = (abs / 100L).toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
        val cents = (abs % 100L).toString().padStart(2, '0')
        return (if (negative) "-" else "") + grouped + "." + cents
    }

    fun withCode(minor: Long, code: String): String =
        if (code.isBlank()) format(minor) else format(minor) + " " + code

    /** Converts a local-currency amount into home-currency minor units. */
    fun convert(localMinor: Long, rate: Double): Long =
        Math.round(localMinor.toDouble() * rate)
}
