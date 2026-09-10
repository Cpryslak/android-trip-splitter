package com.tripsplit.app

/** A traveller. Ids are stable so renaming someone never rewrites history. */
data class Person(
    val id: String,
    val name: String
)

/**
 * The fixed set of expense categories. Fixed so totals mean the same thing
 * across trips; "Other" is the catch-all and blank means not categorised.
 */
object Categories {
    val all: List<String> = listOf(
        "Food", "Drinks", "Lodging", "Transport", "Tickets", "Groceries", "Fuel", "Other"
    )
    const val UNCATEGORISED = "Uncategorised"
}

/**
 * One outlay. [homeMinor] is canonical and always in the trip's home currency;
 * when money was spent abroad we also keep what was actually typed and the rate
 * used at the time, so editing the rate later never silently rewrites the past.
 *
 * [weights] is how the cost divides between [sharedBy]. Null means equally.
 * Otherwise each participant's share is proportional to their weight — small
 * integers for "she had two, I had one", or exact cents for a hand-typed split
 * (which is just weights that happen to add up to the total).
 */
data class Expense(
    val id: String,
    val note: String,
    val payerId: String,
    val sharedBy: List<String>,
    val homeMinor: Long,
    val localMinor: Long? = null,
    val rateUsed: Double? = null,
    val createdAt: Long = 0L,
    val category: String = "",
    val weights: Map<String, Long>? = null
) {
    /** A participant's weight; anyone not listed counts as one share. */
    fun weightOf(personId: String): Long = weights?.get(personId) ?: 1L

    /** True when the participants don't all carry the same weight. */
    val isUneven: Boolean
        get() = weights != null && sharedBy.map { weightOf(it) }.distinct().size > 1
}

/**
 * Money handed directly from one person to another to square up — not a trip
 * cost, so it never gets split. It moves both people's balances and nobody
 * else's.
 */
data class Payment(
    val id: String,
    val fromId: String,
    val toId: String,
    val homeMinor: Long,
    val note: String = "",
    val localMinor: Long? = null,
    val rateUsed: Double? = null,
    val createdAt: Long = 0L
)

data class Trip(
    val id: String = "",
    val createdAt: Long = 0L,
    val name: String = "",
    val homeCurrency: String = "USD",
    val localCurrency: String = "",
    val rate: Double = 1.0,
    val people: List<Person> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val started: Boolean = false
) {
    fun personOf(id: String): Person? = people.firstOrNull { it.id == id }

    fun nameOf(id: String): String = personOf(id)?.name ?: "(removed)"

    val hasLocalCurrency: Boolean
        get() = localCurrency.isNotBlank() && localCurrency != homeCurrency

    /** Trip costs only. Repayments aren't spending. */
    val totalMinor: Long
        get() = expenses.sumOf { it.homeMinor }

    val settledMinor: Long
        get() = payments.sumOf { it.homeMinor }

    /** True when this person appears anywhere in the ledger and can't be removed. */
    fun isReferenced(personId: String): Boolean =
        expenses.any { it.payerId == personId || it.sharedBy.contains(personId) } ||
            payments.any { it.fromId == personId || it.toId == personId }

    /** How many payments are still outstanding on this trip. */
    val outstandingCount: Int
        get() = Settle.pairDebts(this).size

    /** Earliest and latest dated entry, or null when nothing has a date. */
    val firstEntryAt: Long?
        get() = (expenses.map { it.createdAt } + payments.map { it.createdAt })
            .filter { it > 0L }.minOrNull()

    val lastEntryAt: Long?
        get() = (expenses.map { it.createdAt } + payments.map { it.createdAt })
            .filter { it > 0L }.maxOrNull()
}

/**
 * Every trip ever entered, plus which one is open. Old trips stay readable
 * forever — a finished trip is a record, not something to clear out to make
 * room for the next one.
 */
data class Library(
    val trips: List<Trip> = emptyList(),
    val activeId: String = ""
) {
    /** Falls back to the newest trip if the active id has gone missing. */
    val active: Trip?
        get() = trips.firstOrNull { it.id == activeId } ?: byNewest.firstOrNull()

    val byNewest: List<Trip>
        get() = trips.sortedByDescending { it.createdAt }

    fun tripOf(id: String): Trip? = trips.firstOrNull { it.id == id }

    /** Adds or replaces a trip by id, and opens it. */
    fun withTrip(trip: Trip): Library =
        copy(trips = trips.filterNot { it.id == trip.id } + trip, activeId = trip.id)

    /** Adds or replaces a trip by id without changing which one is open. */
    fun updated(trip: Trip): Library =
        copy(trips = trips.filterNot { it.id == trip.id } + trip)

    fun without(tripId: String): Library {
        val remaining = trips.filterNot { it.id == tripId }
        val nextActive = if (activeId == tripId)
            remaining.sortedByDescending { it.createdAt }.firstOrNull()?.id ?: ""
        else activeId
        return copy(trips = remaining, activeId = nextActive)
    }

    fun opening(tripId: String): Library = copy(activeId = tripId)

    /**
     * Folds another library in: same id replaces, new id is added. Restoring a
     * backup can therefore never destroy a trip that isn't in the file.
     */
    fun merge(other: Library): Pair<Library, Pair<Int, Int>> {
        val existingIds = trips.map { it.id }.toSet()
        val replaced = other.trips.count { existingIds.contains(it.id) }
        val added = other.trips.size - replaced
        val kept = trips.filterNot { mine -> other.trips.any { it.id == mine.id } }
        val merged = copy(
            trips = kept + other.trips,
            activeId = other.activeId.ifBlank { activeId }
        )
        return merged to (added to replaced)
    }
}
