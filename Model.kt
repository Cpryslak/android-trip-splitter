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
        get() = Settle.transfers(Settle.balances(this)).size
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

    /** Adds or replaces a trip by id, and opens it. */
    fun withTrip(trip: Trip): Library =
        copy(trips = trips.filterNot { it.id == trip.id } + trip, activeId = trip.id)

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
