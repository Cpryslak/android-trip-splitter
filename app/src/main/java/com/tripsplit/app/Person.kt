package com.tripsplit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One person's whole position: the headline figure, how it was arrived at, who
 * it's with, and every entry they appear in. Reached by tapping a name in the
 * balances list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    trip: Trip,
    personId: String,
    onOpenExpense: (String) -> Unit,
    onOpenPayment: (String) -> Unit,
    onRecordPayment: (String?, String?, Long?) -> Unit,
    onBack: () -> Unit
) {
    val name = trip.nameOf(personId)
    val balance = remember(trip, personId) {
        Settle.balances(trip).firstOrNull { it.personId == personId } ?: Balance(personId, 0L, 0L)
    }
    val net = balance.netMinor
    val debts = remember(trip) { Settle.pairDebts(trip) }
    val owes = debts.filter { it.fromId == personId }
    val owed = debts.filter { it.toId == personId }
    val paid = remember(trip, personId) { Settle.expensesPaidBy(trip, personId) }
    val shared = remember(trip, personId) { Settle.expensesSharedBy(trip, personId) }
    val repayments = remember(trip, personId) { Settle.paymentsInvolving(trip, personId) }
    val categories = remember(trip, personId) { Settle.shareByCategory(trip, personId) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(trip, personId, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            /* ---- the headline ---- */
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    net > 0L -> "+" + Money.withCode(net, trip.homeCurrency)
                    net < 0L -> Money.withCode(net, trip.homeCurrency)
                    else -> "Square"
                },
                style = MaterialTheme.typography.displaySmall,
                color = when {
                    net > 0L -> MoneyGold
                    net < 0L -> MoneyOwed
                    else -> MoneySlate
                }
            )
            Text(
                text = when {
                    net > 0L -> "the group owes " + name
                    net < 0L -> name + " owes the group"
                    else -> "nothing outstanding either way"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MoneySlate
            )

            /* ---- how that figure is built ---- */
            Spacer(Modifier.height(24.dp))
            WorkingRow("Covered for the group", balance.paidMinor, MoneyGold)
            WorkingRow("Own share of everything", -balance.shareMinor, MoneyOwed)
            if (balance.sentMinor > 0L) WorkingRow("Repaid to others", balance.sentMinor, MoneyGold)
            if (balance.receivedMinor > 0L) WorkingRow("Received from others", -balance.receivedMinor, MoneyOwed)
            HorizontalDivider(color = MoneyGold)
            WorkingRow("Net", net, if (net < 0L) MoneyOwed else MoneyGold, bold = true)

            /* ---- with whom ---- */
            if (owes.isNotEmpty() || owed.isNotEmpty()) {
                Section("Who it's with")
                owes.forEach { d ->
                    PairRow(
                        trip = trip,
                        otherId = d.toId,
                        text = "owes " + trip.nameOf(d.toId),
                        amount = d.amountMinor,
                        color = MoneyOwed,
                        onAction = { onRecordPayment(personId, d.toId, d.amountMinor) }
                    )
                }
                owed.forEach { d ->
                    PairRow(
                        trip = trip,
                        otherId = d.fromId,
                        text = "is owed by " + trip.nameOf(d.fromId),
                        amount = d.amountMinor,
                        color = MoneyGold,
                        onAction = { onRecordPayment(d.fromId, personId, d.amountMinor) }
                    )
                }
            }

            /* ---- where their share went ---- */
            if (categories.isNotEmpty() &&
                !(categories.size == 1 && categories[0].first == Categories.UNCATEGORISED)
            ) {
                Section("Own share by category")
                ProportionBar(categories, CategoryColors)
                Spacer(Modifier.height(10.dp))
                categories.forEachIndexed { i, (category, amount) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ColorDot(CategoryColors[i % CategoryColors.size], 10.dp)
                        Spacer(Modifier.width(10.dp))
                        LabelAmountRow(
                            label = category,
                            amountText = Money.format(amount),
                            amountColor = MaterialTheme.colorScheme.onBackground,
                            labelColor = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            /* ---- what they paid for ---- */
            if (paid.isNotEmpty()) {
                Section("Paid for " + paid.size + (if (paid.size == 1) " thing" else " things"))
                paid.forEach { e ->
                    val own = Settle.shareOf(e, personId)
                    EntryRow(
                        trip = trip,
                        title = if (e.note.isBlank()) "Expense" else e.note,
                        subtitle = listOfNotNull(
                            Dates.shortDate(e.createdAt).ifBlank { null },
                            e.category.ifBlank { null },
                            "split " + e.sharedBy.size +
                                (if (e.sharedBy.size == 1) " way" else " ways") +
                                (if (e.isUneven) ", unevenly" else ""),
                            "own share " + Money.format(own),
                            "others owe " + Money.format(e.homeMinor - own)
                        ).joinToString(" · "),
                        participants = e.sharedBy,
                        amount = e.homeMinor,
                        amountColor = MaterialTheme.colorScheme.onBackground,
                        onClick = { onOpenExpense(e.id) }
                    )
                }
            }

            /* ---- what they owe a share of ---- */
            if (shared.isNotEmpty()) {
                Section(
                    "Owes a share of " + shared.size +
                        (if (shared.size == 1) " thing" else " things")
                )
                shared.forEach { e ->
                    EntryRow(
                        trip = trip,
                        title = if (e.note.isBlank()) "Expense" else e.note,
                        subtitle = listOfNotNull(
                            Dates.shortDate(e.createdAt).ifBlank { null },
                            e.category.ifBlank { null },
                            trip.nameOf(e.payerId) + " paid " + Money.format(e.homeMinor),
                            "split " + e.sharedBy.size +
                                (if (e.sharedBy.size == 1) " way" else " ways") +
                                (if (e.isUneven) ", unevenly" else "")
                        ).joinToString(" · "),
                        participants = e.sharedBy,
                        amount = Settle.shareOf(e, personId),
                        amountColor = MoneyOwed,
                        onClick = { onOpenExpense(e.id) }
                    )
                }
            }

            /* ---- repayments ---- */
            if (repayments.isNotEmpty()) {
                Section("Repayments")
                repayments.forEach { p ->
                    val sending = p.fromId == personId
                    EntryRow(
                        trip = trip,
                        title = if (sending) "paid " + trip.nameOf(p.toId)
                        else "received from " + trip.nameOf(p.fromId),
                        subtitle = listOfNotNull(
                            Dates.shortDate(p.createdAt).ifBlank { null },
                            if (p.note.isBlank()) "repayment" else p.note
                        ).joinToString(" · "),
                        participants = emptyList(),
                        amount = p.homeMinor,
                        amountColor = if (sending) MoneyGold else MoneyOwed,
                        onClick = { onOpenPayment(p.id) }
                    )
                }
            }

            if (paid.isEmpty() && shared.isEmpty() && repayments.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    name + " isn't in any entry yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MoneySlate
                )
            }

            Spacer(Modifier.height(44.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(30.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun WorkingRow(
    label: String,
    amountMinor: Long,
    color: Color,
    bold: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (bold) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyLarge,
            color = if (bold) MaterialTheme.colorScheme.onBackground else MoneySlate,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = (if (amountMinor > 0L) "+" else "") + Money.format(amountMinor),
            style = MaterialTheme.typography.titleMedium,
            color = color
        )
    }
}

@Composable
private fun PairRow(
    trip: Trip,
    otherId: String,
    text: String,
    amount: Long,
    color: Color,
    onAction: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(trip, otherId, 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(Money.format(amount), style = MaterialTheme.typography.titleMedium, color = color)
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onAction) { Text("Mark paid") }
    }
}

@Composable
private fun EntryRow(
    trip: Trip,
    title: String,
    subtitle: String,
    participants: List<String>,
    amount: Long,
    amountColor: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MoneySlate)
            if (participants.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                AvatarRow(trip, participants, 18.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(Money.format(amount), style = MaterialTheme.typography.titleMedium, color = amountColor)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}
