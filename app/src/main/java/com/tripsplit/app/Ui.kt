package com.tripsplit.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

sealed interface Screen {
    data object Ledger : Screen
    data object Setup : Screen
    data class Edit(val expenseId: String?) : Screen
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var trip by remember { mutableStateOf(Store.load(ctx)) }
    var screen by remember { mutableStateOf<Screen>(Screen.Ledger) }

    val commit: (Trip) -> Unit = { next ->
        trip = next
        Store.save(ctx, next)
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (!trip.started) {
            SetupScreen(
                trip = trip,
                firstRun = true,
                onSave = { commit(it); screen = Screen.Ledger },
                onCancel = null
            )
        } else when (val s = screen) {
            is Screen.Setup -> SetupScreen(
                trip = trip,
                firstRun = false,
                onSave = { commit(it); screen = Screen.Ledger },
                onCancel = { screen = Screen.Ledger }
            )
            is Screen.Edit -> EditExpenseScreen(
                trip = trip,
                existing = trip.expenses.firstOrNull { it.id == s.expenseId },
                onSave = { updated ->
                    val others = trip.expenses.filterNot { it.id == updated.id }
                    commit(trip.copy(expenses = (others + updated).sortedByDescending { it.createdAt }))
                    screen = Screen.Ledger
                },
                onDelete = { id ->
                    commit(trip.copy(expenses = trip.expenses.filterNot { it.id == id }))
                    screen = Screen.Ledger
                },
                onCancel = { screen = Screen.Ledger }
            )
            is Screen.Ledger -> LedgerScreen(
                trip = trip,
                onAdd = { screen = Screen.Edit(null) },
                onOpen = { id -> screen = Screen.Edit(id) },
                onSetup = { screen = Screen.Setup }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerScreen(
    trip: Trip,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onSetup: () -> Unit
) {
    val ctx = LocalContext.current
    val share: () -> Unit = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, if (trip.name.isBlank()) "Trip" else trip.name)
            putExtra(Intent.EXTRA_TEXT, Settle.summary(trip))
        }
        ctx.startActivity(Intent.createChooser(intent, "Send settle-up"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Column {
                        Text(
                            text = if (trip.name.isBlank()) "Trip Split" else trip.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = Money.withCode(trip.totalMinor, trip.homeCurrency) +
                                " · " + trip.expenses.size + " entries",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MoneySlate
                        )
                    }
                },
                actions = {
                    IconButton(onClick = share) {
                        Icon(Icons.Default.Share, contentDescription = "Send settle-up")
                    }
                    IconButton(onClick = onSetup) {
                        Icon(Icons.Default.Settings, contentDescription = "Trip settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = MoneyGold) {
                Icon(Icons.Default.Add, contentDescription = "Add an expense")
            }
        }
    ) { inner ->
        BoxWithConstraints(modifier = Modifier.padding(inner).fillMaxSize()) {
            val twoPane = maxWidth >= 840.dp
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1.15f).fillMaxHeight()) {
                        ExpenseList(trip, onOpen)
                    }
                    VerticalRule()
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        BalancesPane(trip)
                    }
                }
            } else {
                var tab by remember { mutableStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.background
                    ) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Expenses") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Balances") })
                    }
                    if (tab == 0) {
                        ExpenseList(trip, onOpen)
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp)
                        ) {
                            BalancesPane(trip)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun ExpenseList(trip: Trip, onOpen: (String) -> Unit) {
    if (trip.expenses.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopStart) {
            Column {
                Text("Nothing logged yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap the plus whenever someone pays for something. " +
                        "Say who paid and who it covers, and the settle-up works itself out.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MoneySlate
                )
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(trip.expenses, key = { it.id }) { e ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(e.id) }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (e.note.isBlank()) "Expense" else e.note,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = trip.nameOf(e.payerId) + " paid · split " +
                                e.sharedBy.size + (if (e.sharedBy.size == 1) " way" else " ways"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MoneySlate
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = Money.format(e.homeMinor),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (e.localMinor != null) {
                            Text(
                                text = Money.format(e.localMinor) + " " + trip.localCurrency,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MoneySlate
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun BalancesPane(trip: Trip) {
    val balances = Settle.balances(trip)
    val moves = Settle.transfers(balances)

    Column {
        Text("Where everyone stands", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))

        balances.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(trip.nameOf(b.personId), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "paid " + Money.format(b.paidMinor) + " · owes " + Money.format(b.shareMinor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MoneySlate
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val net = b.netMinor
                    Text(
                        text = when {
                            net > 0L -> "+" + Money.format(net)
                            net < 0L -> Money.format(net)
                            else -> "square"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            net > 0L -> MoneyGold
                            net < 0L -> MoneyOwed
                            else -> MoneySlate
                        }
                    )
                    Text(
                        text = when {
                            net > 0L -> "is owed"
                            net < 0L -> "owes"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MoneySlate
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MoneyGold)
        Spacer(Modifier.height(18.dp))

        Text("Settle up", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        if (moves.isEmpty()) {
            Text(
                if (trip.expenses.isEmpty()) "Nothing to settle yet."
                else "Nobody owes anybody. Everyone is square.",
                style = MaterialTheme.typography.bodyLarge,
                color = MoneySlate
            )
        } else {
            Text(
                "The fewest payments that clear everything:",
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
            Spacer(Modifier.height(10.dp))
            moves.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = trip.nameOf(t.fromId) + "  →  " + trip.nameOf(t.toId),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = Money.withCode(t.amountMinor, trip.homeCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        color = MoneyGold
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
