package com.tripsplit.app

data class Balance(
    val personId: String,
    val paidMinor: Long,
    val shareMinor: Long
) {
    /** Positive means the group owes them. */
    val netMinor: Long get() = paidMinor - shareMinor
}

data class Transfer(
    val fromId: String,
    val toId: String,
    val amountMinor: Long
)

object Settle {

    /**
     * Divides an amount into whole cents. A 100c dinner for three is 34/33/33,
     * not three lots of 33.33 that quietly lose a cent. The extra cents go to
     * the earliest ids in a stable order so the parts always re-sum to the whole.
     */
    fun shares(totalMinor: Long, personIds: List<String>): Map<String, Long> {
        val ordered = personIds.distinct().sorted()
        if (ordered.isEmpty()) return emptyMap()
        val base = totalMinor / ordered.size
        val remainder = (totalMinor % ordered.size).toInt()
        val out = LinkedHashMap<String, Long>()
        ordered.forEachIndexed { i, id ->
            out[id] = base + if (i < remainder) 1L else 0L
        }
        return out
    }

    fun balances(trip: Trip): List<Balance> {
        val paid = HashMap<String, Long>()
        val owed = HashMap<String, Long>()
        for (e in trip.expenses) {
            paid[e.payerId] = (paid[e.payerId] ?: 0L) + e.homeMinor
            for ((id, share) in shares(e.homeMinor, e.sharedBy)) {
                owed[id] = (owed[id] ?: 0L) + share
            }
        }
        return trip.people.map { p ->
            Balance(p.id, paid[p.id] ?: 0L, owed[p.id] ?: 0L)
        }
    }

    /**
     * Fewest payments that clear the board: the largest debtor pays the largest
     * creditor, repeat. Never more than (people - 1) transfers.
     */
    fun transfers(balances: List<Balance>): List<Transfer> {
        val creditors = balances.filter { it.netMinor > 0 }
            .sortedWith(compareByDescending<Balance> { it.netMinor }.thenBy { it.personId })
        val debtors = balances.filter { it.netMinor < 0 }
            .sortedWith(compareBy<Balance> { it.netMinor }.thenBy { it.personId })

        val creditIds = creditors.map { it.personId }
        val credit = LongArray(creditors.size) { creditors[it].netMinor }
        val debtIds = debtors.map { it.personId }
        val debt = LongArray(debtors.size) { -debtors[it].netMinor }

        val out = ArrayList<Transfer>()
        var d = 0
        var c = 0
        while (d < debt.size && c < credit.size) {
            val amount = minOf(debt[d], credit[c])
            if (amount > 0L) out.add(Transfer(debtIds[d], creditIds[c], amount))
            debt[d] -= amount
            credit[c] -= amount
            if (debt[d] == 0L) d++
            if (credit[c] == 0L) c++
        }
        return out
    }

    /** Plain-text summary, for sending to the group chat. */
    fun summary(trip: Trip): String {
        val sb = StringBuilder()
        val title = if (trip.name.isBlank()) "Trip" else trip.name
        sb.append(title).append("\n")
        sb.append("Total spent: ").append(Money.withCode(trip.totalMinor, trip.homeCurrency))
        sb.append("\n\n")
        for (b in balances(trip)) {
            sb.append(trip.nameOf(b.personId))
                .append(" — paid ").append(Money.format(b.paidMinor))
                .append(", share ").append(Money.format(b.shareMinor))
                .append("\n")
        }
        val moves = transfers(balances(trip))
        sb.append("\nSettle up:\n")
        if (moves.isEmpty()) {
            sb.append("Nothing owed. Everyone is square.\n")
        } else {
            for (t in moves) {
                sb.append("  ").append(trip.nameOf(t.fromId))
                    .append(" pays ").append(trip.nameOf(t.toId))
                    .append(" ").append(Money.withCode(t.amountMinor, trip.homeCurrency))
                    .append("\n")
            }
        }
        return sb.toString()
    }
}
