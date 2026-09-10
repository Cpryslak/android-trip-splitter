package com.tripsplit.app

data class Balance(
    val personId: String,
    val paidMinor: Long,
    val shareMinor: Long,
    val sentMinor: Long = 0L,
    val receivedMinor: Long = 0L
) {
    /**
     * Positive means the group still owes them. Covering costs and handing
     * someone cash both move you the same direction; being handed cash moves
     * you back.
     */
    val netMinor: Long get() = paidMinor - shareMinor + sentMinor - receivedMinor
}

data class Transfer(
    val fromId: String,
    val toId: String,
    val amountMinor: Long
)

/** One reason inside a pair's balance: an expense share, or a repayment. */
data class DebtLine(
    val label: String,
    val amountMinor: Long,
    val expenseId: String? = null,
    val paymentId: String? = null
)

/**
 * What one person owes another, counting only what actually passed between the
 * two of them. [lines] is the working: positive entries add to the debt,
 * negative ones (the other direction, or a repayment) subtract.
 */
data class PairDebt(
    val fromId: String,
    val toId: String,
    val amountMinor: Long,
    val lines: List<DebtLine>
)

object Settle {

    /**
     * Divides an amount into whole cents in proportion to [weights] (equal when
     * null). A 100c dinner for three is 34/33/33, not three lots of 33.33 that
     * quietly lose a cent. Leftover cents go to whoever was rounded down the
     * most, ties broken by id, so the parts always re-sum to the whole and the
     * same inputs always give the same answer.
     *
     * Weights that are exact cents summing to the total come back unchanged,
     * which is what makes a hand-typed split just another set of weights.
     */
    fun shares(
        totalMinor: Long,
        personIds: List<String>,
        weights: Map<String, Long>? = null
    ): Map<String, Long> {
        val ordered = personIds.distinct().sorted()
        if (ordered.isEmpty()) return emptyMap()

        val w = LongArray(ordered.size) { i -> (weights?.get(ordered[i]) ?: 1L).coerceAtLeast(0L) }
        val sumW = w.sum()
        if (sumW <= 0L) return shares(totalMinor, personIds, null)

        val floors = LongArray(ordered.size)
        val remainders = LongArray(ordered.size)
        for (i in ordered.indices) {
            val scaled = totalMinor * w[i]
            floors[i] = Math.floorDiv(scaled, sumW)
            remainders[i] = Math.floorMod(scaled, sumW)
        }
        var left = totalMinor - floors.sum()
        val byRemainder = ordered.indices.sortedWith(
            compareByDescending<Int> { remainders[it] }.thenBy { it }
        )
        for (i in byRemainder) {
            if (left <= 0L) break
            if (w[i] > 0L) {
                floors[i] += 1L
                left -= 1L
            }
        }

        val out = LinkedHashMap<String, Long>()
        ordered.forEachIndexed { i, id -> out[id] = floors[i] }
        return out
    }

    fun shares(expense: Expense): Map<String, Long> =
        shares(expense.homeMinor, expense.sharedBy, expense.weights)

    fun balances(trip: Trip): List<Balance> {
        val paid = HashMap<String, Long>()
        val owed = HashMap<String, Long>()
        val sent = HashMap<String, Long>()
        val received = HashMap<String, Long>()

        for (e in trip.expenses) {
            paid[e.payerId] = (paid[e.payerId] ?: 0L) + e.homeMinor
            for ((id, share) in shares(e)) {
                owed[id] = (owed[id] ?: 0L) + share
            }
        }
        for (p in trip.payments) {
            if (p.fromId == p.toId) continue // nonsense, and would cancel itself anyway
            sent[p.fromId] = (sent[p.fromId] ?: 0L) + p.homeMinor
            received[p.toId] = (received[p.toId] ?: 0L) + p.homeMinor
        }

        return trip.people.map { p ->
            Balance(
                personId = p.id,
                paidMinor = paid[p.id] ?: 0L,
                shareMinor = owed[p.id] ?: 0L,
                sentMinor = sent[p.id] ?: 0L,
                receivedMinor = received[p.id] ?: 0L
            )
        }
    }

    /**
     * Fewest payments that clear what's left: the largest debtor pays the
     * largest creditor, repeat. Never more than (people - 1) transfers.
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
            // A person can only be a debtor or a creditor, never both, so this
            // should be unreachable. Guarded anyway: telling someone to pay
            // themselves is worse than showing one fewer line.
            if (amount > 0L && debtIds[d] != creditIds[c]) {
                out.add(Transfer(debtIds[d], creditIds[c], amount))
            }
            debt[d] -= amount
            credit[c] -= amount
            if (debt[d] == 0L) d++
            if (credit[c] == 0L) c++
        }
        return out
    }

    /**
     * Who owes whom, pair by pair. Only expenses the two people actually shared
     * count, so nobody is ever told to pay someone they never transacted with —
     * which is what global netting does, and what made the old screen confusing.
     *
     * The sum of a person's pair debts always equals their net in [balances], so
     * this can't disagree with the balances above it.
     */
    fun pairDebts(trip: Trip): List<PairDebt> {
        val ids = trip.people.map { it.id }
        val shareCache = trip.expenses.associate { it.id to shares(it) }
        val out = ArrayList<PairDebt>()

        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val a = ids[i]
                val b = ids[j]
                var balance = 0L // positive means a owes b
                val lines = ArrayList<DebtLine>()

                for (e in trip.expenses) {
                    val sh = shareCache[e.id] ?: continue
                    val label = if (e.note.isBlank()) "Expense" else e.note
                    if (e.payerId == b) {
                        val owed = sh[a] ?: 0L
                        if (owed > 0L) {
                            balance += owed
                            lines.add(DebtLine(label, owed, expenseId = e.id))
                        }
                    } else if (e.payerId == a) {
                        val owed = sh[b] ?: 0L
                        if (owed > 0L) {
                            balance -= owed
                            lines.add(DebtLine(label, -owed, expenseId = e.id))
                        }
                    }
                }

                for (p in trip.payments) {
                    if (p.fromId == p.toId) continue
                    val label = if (p.note.isBlank()) "Repayment" else "Repayment · " + p.note
                    if (p.fromId == a && p.toId == b) {
                        balance -= p.homeMinor
                        lines.add(DebtLine(label, -p.homeMinor, paymentId = p.id))
                    } else if (p.fromId == b && p.toId == a) {
                        balance += p.homeMinor
                        lines.add(DebtLine(label, p.homeMinor, paymentId = p.id))
                    }
                }

                when {
                    balance > 0L -> out.add(PairDebt(a, b, balance, lines))
                    balance < 0L -> out.add(
                        // Flip the direction and the working with it.
                        PairDebt(b, a, -balance, lines.map { it.copy(amountMinor = -it.amountMinor) })
                    )
                }
            }
        }
        return out
    }

    /** The same debts grouped under whoever has to pay them, biggest debtor first. */
    fun debtsByPerson(trip: Trip): List<Pair<String, List<PairDebt>>> =
        pairDebts(trip)
            .groupBy { it.fromId }
            .map { (from, debts) -> from to debts.sortedByDescending { it.amountMinor } }
            .sortedByDescending { (_, debts) -> debts.sumOf { it.amountMinor } }

    /** Everything one person is involved in, for their own page. */
    fun expensesPaidBy(trip: Trip, personId: String): List<Expense> =
        trip.expenses.filter { it.payerId == personId }.sortedByDescending { it.createdAt }

    fun expensesSharedBy(trip: Trip, personId: String): List<Expense> =
        trip.expenses
            .filter { it.payerId != personId && it.sharedBy.contains(personId) }
            .sortedByDescending { it.createdAt }

    fun paymentsInvolving(trip: Trip, personId: String): List<Payment> =
        trip.payments
            .filter { it.fromId == personId || it.toId == personId }
            .sortedByDescending { it.createdAt }

    /** This person's slice of one expense. */
    fun shareOf(expense: Expense, personId: String): Long =
        shares(expense)[personId] ?: 0L

    /** Trip spending per category, biggest first. Blank categories pool together. */
    fun byCategory(trip: Trip): List<Pair<String, Long>> =
        trip.expenses
            .groupBy { it.category.ifBlank { Categories.UNCATEGORISED } }
            .map { (category, list) -> category to list.sumOf { it.homeMinor } }
            .filter { it.second != 0L }
            .sortedByDescending { it.second }

    /** One person's own share of spending per category, biggest first. */
    fun shareByCategory(trip: Trip, personId: String): List<Pair<String, Long>> =
        trip.expenses
            .filter { it.sharedBy.contains(personId) }
            .groupBy { it.category.ifBlank { Categories.UNCATEGORISED } }
            .map { (category, list) -> category to list.sumOf { shareOf(it, personId) } }
            .filter { it.second != 0L }
            .sortedByDescending { it.second }

    /** Plain-text summary, for sending to the group chat. */
    fun summary(trip: Trip): String {
        val sb = StringBuilder()
        val title = if (trip.name.isBlank()) "Trip" else trip.name
        sb.append(title).append("\n")
        sb.append("Total spent: ").append(Money.withCode(trip.totalMinor, trip.homeCurrency)).append("\n")
        if (trip.settledMinor > 0L) {
            sb.append("Already settled: ")
                .append(Money.withCode(trip.settledMinor, trip.homeCurrency)).append("\n")
        }
        val categories = byCategory(trip)
        if (categories.size > 1 || (categories.size == 1 && categories[0].first != Categories.UNCATEGORISED)) {
            sb.append("By category: ")
                .append(categories.joinToString(", ") { it.first + " " + Money.format(it.second) })
                .append("\n")
        }
        sb.append("\n")

        for (b in balances(trip)) {
            sb.append(trip.nameOf(b.personId))
                .append(" — paid ").append(Money.format(b.paidMinor))
                .append(", share ").append(Money.format(b.shareMinor))
            if (b.sentMinor > 0L) sb.append(", repaid ").append(Money.format(b.sentMinor))
            if (b.receivedMinor > 0L) sb.append(", received ").append(Money.format(b.receivedMinor))
            sb.append("\n")
        }

        val grouped = debtsByPerson(trip)
        sb.append("\nStill to settle:\n")
        if (grouped.isEmpty()) {
            sb.append("Nothing owed. Everyone is square.\n")
        } else {
            for ((fromId, debts) in grouped) {
                sb.append("  ").append(trip.nameOf(fromId)).append(" owes ")
                    .append(Money.withCode(debts.sumOf { it.amountMinor }, trip.homeCurrency))
                    .append("\n")
                for (d in debts) {
                    sb.append("      to ").append(trip.nameOf(d.toId))
                        .append(" ").append(Money.format(d.amountMinor)).append("\n")
                }
            }
        }
        return sb.toString()
    }
}
