package com.tripsplit.app

/** A traveller. Ids are stable so renaming someone never rewrites history. */
data class Person(
    val id: String,
    val name: String
)

/**
 * One outlay. [homeMinor] is canonical and always in the trip's home currency;
 * when money was spent abroad we also keep what was actually typed and the rate
 * used at the time, so editing the rate later never silently rewrites the past.
 */
data class Expense(
    val id: String,
    val note: String,
    val payerId: String,
    val sharedBy: List<String>,
    val homeMinor: Long,
    val localMinor: Long? = null,
    val rateUsed: Double? = null,
    val createdAt: Long = 0L
)

data class Trip(
    val name: String = "",
    val homeCurrency: String = "USD",
    val localCurrency: String = "",
    val rate: Double = 1.0,
    val people: List<Person> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val started: Boolean = false
) {
    fun personOf(id: String): Person? = people.firstOrNull { it.id == id }

    fun nameOf(id: String): String = personOf(id)?.name ?: "(removed)"

    val hasLocalCurrency: Boolean
        get() = localCurrency.isNotBlank() && localCurrency != homeCurrency

    val totalMinor: Long
        get() = expenses.sumOf { it.homeMinor }

    /** True when this person appears anywhere in the ledger and can't be removed. */
    fun isReferenced(personId: String): Boolean =
        expenses.any { it.payerId == personId || it.sharedBy.contains(personId) }
}
