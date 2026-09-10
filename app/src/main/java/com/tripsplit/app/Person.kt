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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onCancel: () -> Unit
) {
    val name = trip.nameOf(personId)
    val balance = Settle.balances(trip).firstOrNull { it.personId == personId }
        ?: Balance(personId, 0L, 0L)
    val net = balance.netMinor
    val owes = Settle.pairDebts(trip).filter { it.fromId == personId }
    val owed = Settle.pairDebts(trip).filter { it.toId == personId }
    val paid = Settle.expensesPaidBy(trip, personId)
    val shared = Settle.expensesSharedBy(trip, personId)
    val repayments = Settle.paymentsInvolving(trip, personId)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
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
                        text = "owes " + trip.nameOf(d.toId),
                        amount = d.amountMinor,
                        color = MoneyOwed,
                        actionLabel = "Mark paid",
                        onAction = { onRecordPayment(personId, d.toId, d.amountMinor) }
                    )
                }
                owed.forEach { d ->
                    PairRow(
                        text = "is owed by " + trip.nameOf(d.fromId),
                        amount = d.amountMinor,
                        color = MoneyGold,
                        actionLabel = "Mark paid",
                        onAction = { onRecordPayment(d.fromId, personId, d.amountMinor) }
                    )
                }
            }

            /* ---- what they paid for ---- */
            if (paid.isNotEmpty()) {
                Section("Paid for " + paid.size + (if (paid.size == 1) " thing" else " things"))
                paid.forEach { e ->
                    val own = Settle.shareOf(e, personId)
                    EntryRow(
                        title = if (e.note.isBlank()) "Expense" else e.note,
                        subtitle = "split " + e.sharedBy.size +
                            (if (e.sharedBy.size == 1) " way" else " ways") +
                            " · own share " + Money.format(own) +
                            " · others owe " + Money.format(e.homeMinor - own),
                        amount = e.homeMinor,
                        amountColor = MaterialTheme.colorScheme.onBackground,
                        onClick = { onOpenExpense(e.id) }
                    )
                }
            }

            /* ---- what they owe a share of ---- */
            if (shared.isNotEmpty()) {
                Section("Owes a share of " + shared.size +
                    (if (shared.size == 1) " thing" else " things"))
                shared.forEach { e ->
                    EntryRow(
                        title = if (e.note.isBlank()) "Expense" else e.note,
                        subtitle = trip.nameOf(e.payerId) + " paid " + Money.format(e.homeMinor) +
                            " · split " + e.sharedBy.size +
                            (if (e.sharedBy.size == 1) " way" else " ways"),
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
                        title = if (sending) "paid " + trip.nameOf(p.toId)
                        else "received from " + trip.nameOf(p.fromId),
                        subtitle = if (p.note.isBlank()) "repayment" else p.note,
                        amount = p.homeMinor,
                        amountColor = if (sending) MoneyGold else MoneySlate,
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
    color: androidx.compose.ui.graphics.Color,
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
    text: String,
    amount: Long,
    color: androidx.compose.ui.graphics.Color,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(Money.format(amount), style = MaterialTheme.typography.titleMedium, color = color)
        Spacer(Modifier.width(14.dp))
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MoneyGold,
            modifier = Modifier.clickable { onAction() }
        )
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    amount: Long,
    amountColor: androidx.compose.ui.graphics.Color,
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
        }
        Spacer(Modifier.width(12.dp))
        Text(Money.format(amount), style = MaterialTheme.typography.titleMedium, color = amountColor)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}
