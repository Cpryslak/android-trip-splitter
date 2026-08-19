package com.tripsplit.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    data object NewTrip : Screen
    data class Edit(val expenseId: String?) : Screen
    data class Pay(
        val paymentId: String?,
        val fromId: String? = null,
        val toId: String? = null,
        val amountMinor: Long? = null
    ) : Screen
}

/**
 * A fresh trip, carrying over the people from the last one — the same group
 * tends to travel together, and the names are the tedious part to retype.
 */
private fun blankTrip(library: Library): Trip = Trip(
    id = java.util.UUID.randomUUID().toString().take(8),
    createdAt = System.currentTimeMillis(),
    homeCurrency = library.active?.homeCurrency ?: "USD",
    people = library.active?.people ?: emptyList(),
    started = false
)

/** Expenses and repayments share one chronological ledger. */
private sealed interface Entry {
    val at: Long
    data class Exp(val e: Expense) : Entry {
        override val at: Long get() = e.createdAt
    }
    data class Pay(val p: Payment) : Entry {
        override val at: Long get() = p.createdAt
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var library by remember { mutableStateOf(Store.load(ctx)) }
    var screen by remember { mutableStateOf<Screen>(Screen.Ledger) }

    val commitLibrary: (Library) -> Unit = { next ->
        library = next
        Store.save(ctx, next)
    }
    val commit: (Trip) -> Unit = { next -> commitLibrary(library.withTrip(next)) }

    val trip = library.active

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (trip == null || !trip.started) {
            // No trips yet, or the only one was never finished being set up.
            SetupScreen(
                trip = trip ?: blankTrip(library),
                firstRun = true,
                onSave = { commit(it); screen = Screen.Ledger },
                onCancel = null,
                onDeleteTrip = null
            )
        } else when (val s = screen) {
            is Screen.NewTrip -> SetupScreen(
                trip = blankTrip(library),
                firstRun = true,
                onSave = { commit(it); screen = Screen.Ledger },
                onCancel = { screen = Screen.Ledger },
                onDeleteTrip = null
            )
            is Screen.Setup -> SetupScreen(
                trip = trip,
                firstRun = false,
                onSave = { commit(it); screen = Screen.Ledger },
                onCancel = { screen = Screen.Ledger },
                onDeleteTrip = { id ->
                    commitLibrary(library.without(id))
                    screen = Screen.Ledger
                }
            )
            is Screen.Edit -> EditExpenseScreen(
                trip = trip,
                existing = trip.expenses.firstOrNull { it.id == s.expenseId },
                onSave = { updated ->
                    val others = trip.expenses.filterNot { it.id == updated.id }
                    commit(trip.copy(expenses = others + updated))
                    screen = Screen.Ledger
                },
                onDelete = { id ->
                    commit(trip.copy(expenses = trip.expenses.filterNot { it.id == id }))
                    screen = Screen.Ledger
                },
                onCancel = { screen = Screen.Ledger }
            )
            is Screen.Pay -> PaymentScreen(
                trip = trip,
                existing = trip.payments.firstOrNull { it.id == s.paymentId },
                suggestedFromId = s.fromId,
                suggestedToId = s.toId,
                suggestedMinor = s.amountMinor,
                onSave = { updated ->
                    val others = trip.payments.filterNot { it.id == updated.id }
                    commit(trip.copy(payments = others + updated))
                    screen = Screen.Ledger
                },
                onDelete = { id ->
                    commit(trip.copy(payments = trip.payments.filterNot { it.id == id }))
                    screen = Screen.Ledger
                },
                onCancel = { screen = Screen.Ledger }
            )
            is Screen.Ledger -> LedgerScreen(
                library = library,
                trip = trip,
                onAdd = { screen = Screen.Edit(null) },
                onOpenExpense = { id -> screen = Screen.Edit(id) },
                onOpenPayment = { id -> screen = Screen.Pay(id) },
                onRecordPayment = { from, to, amount ->
                    screen = Screen.Pay(null, from, to, amount)
                },
                onSetup = { screen = Screen.Setup },
                onNewTrip = { screen = Screen.NewTrip },
                onOpenTrip = { id -> commitLibrary(library.opening(id)) },
                onRestore = { restored ->
                    val (merged, counts) = library.merge(restored)
                    commitLibrary(merged)
                    counts
                }
            )
        }
    }
}

/** Which pane is showing. Kept semantic so a rotation can't land on the wrong one. */
private enum class Pane { Ledger, Balances, Trips }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerScreen(
    library: Library,
    trip: Trip,
    onAdd: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onOpenPayment: (String) -> Unit,
    onRecordPayment: (String?, String?, Long?) -> Unit,
    onSetup: () -> Unit,
    onNewTrip: () -> Unit,
    onOpenTrip: (String) -> Unit,
    onRestore: (Library) -> Pair<Int, Int>
) {
    var pane by remember { mutableStateOf(Pane.Ledger) }
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
                            text = Money.withCode(trip.totalMinor, trip.homeCurrency) + " spent" +
                                if (trip.settledMinor > 0L)
                                    " · " + Money.format(trip.settledMinor) + " settled" else "",
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
            FloatingActionButton(
                onClick = { if (pane == Pane.Trips) onNewTrip() else onAdd() },
                containerColor = MoneyGold
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (pane == Pane.Trips) "Start a new trip"
                    else "Add an expense"
                )
            }
        }
    ) { inner ->
        BoxWithConstraints(modifier = Modifier.padding(inner).fillMaxSize()) {
            // Wide enough and the ledger and balances sit side by side, so the
            // only tabs needed are this trip and all of them.
            val twoPane = maxWidth >= 840.dp
            val panes = if (twoPane) listOf(Pane.Ledger, Pane.Trips)
            else listOf(Pane.Ledger, Pane.Balances, Pane.Trips)
            val shown = if (panes.contains(pane)) pane else Pane.Ledger

            Column(Modifier.fillMaxSize()) {
                TabRow(
                    selectedTabIndex = panes.indexOf(shown),
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    panes.forEach { p ->
                        Tab(
                            selected = shown == p,
                            onClick = { pane = p },
                            text = {
                                Text(
                                    when (p) {
                                        Pane.Ledger -> if (twoPane) "This trip" else "Ledger"
                                        Pane.Balances -> "Balances"
                                        Pane.Trips -> "All trips"
                                    }
                                )
                            }
                        )
                    }
                }

                when (shown) {
                    Pane.Ledger -> if (twoPane) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1.15f).fillMaxHeight()) {
                                LedgerList(trip, onOpenExpense, onOpenPayment)
                            }
                            VerticalRule()
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp)
                            ) {
                                BalancesPane(trip, onRecordPayment)
                            }
                        }
                    } else {
                        LedgerList(trip, onOpenExpense, onOpenPayment)
                    }

                    Pane.Balances -> Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        BalancesPane(trip, onRecordPayment)
                    }

                    Pane.Trips -> Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        TripsPane(
                            library = library,
                            onOpenTrip = { id ->
                                onOpenTrip(id)
                                pane = Pane.Ledger
                            },
                            onNewTrip = onNewTrip,
                            onRestore = onRestore
                        )
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
private fun LedgerList(
    trip: Trip,
    onOpenExpense: (String) -> Unit,
    onOpenPayment: (String) -> Unit
) {
    val entries: List<Entry> = remember(trip) {
        (trip.expenses.map { Entry.Exp(it) } + trip.payments.map { Entry.Pay(it) })
            .sortedByDescending { it.at }
    }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopStart) {
            Column {
                Text("Nothing logged yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap the plus whenever someone pays for something. Say who paid " +
                        "and who it covers, and the settle-up works itself out.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MoneySlate
                )
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            items = entries,
            key = { entry ->
                when (entry) {
                    is Entry.Exp -> "e" + entry.e.id
                    is Entry.Pay -> "p" + entry.p.id
                }
            }
        ) { entry ->
            when (entry) {
                is Entry.Exp -> ExpenseRow(trip, entry.e) { onOpenExpense(entry.e.id) }
                is Entry.Pay -> PaymentRow(trip, entry.p) { onOpenPayment(entry.p.id) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ExpenseRow(trip: Trip, e: Expense, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (e.note.isBlank()) "Expense" else e.note,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = trip.nameOf(e.payerId) + " paid · split " + e.sharedBy.size +
                    (if (e.sharedBy.size == 1) " way" else " ways"),
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Money.format(e.homeMinor), style = MaterialTheme.typography.titleMedium)
            if (e.localMinor != null) {
                Text(
                    Money.format(e.localMinor) + " " + trip.localCurrency,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MoneySlate
                )
            }
        }
    }
}

@Composable
private fun PaymentRow(trip: Trip, p: Payment, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = trip.nameOf(p.fromId) + "  →  " + trip.nameOf(p.toId),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (p.note.isBlank()) "repayment" else "repayment · " + p.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Money.format(p.homeMinor),
                style = MaterialTheme.typography.titleMedium,
                color = MoneyGold
            )
            if (p.localMinor != null) {
                Text(
                    Money.format(p.localMinor) + " " + trip.localCurrency,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MoneySlate
                )
            }
        }
    }
}

@Composable
private fun BalancesPane(
    trip: Trip,
    onRecordPayment: (String?, String?, Long?) -> Unit
) {
    val balances = Settle.balances(trip)
    val moves = Settle.transfers(balances)

    Column {
        Text("Where everyone stands", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))

        balances.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(trip.nameOf(b.personId), style = MaterialTheme.typography.titleMedium)
                    val detail = StringBuilder()
                    detail.append("paid ").append(Money.format(b.paidMinor))
                    detail.append(" · owes ").append(Money.format(b.shareMinor))
                    if (b.sentMinor > 0L) detail.append(" · repaid ").append(Money.format(b.sentMinor))
                    if (b.receivedMinor > 0L) detail.append(" · got back ").append(Money.format(b.receivedMinor))
                    Text(
                        detail.toString(),
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

        Text("Still to settle", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))

        if (moves.isEmpty()) {
            Text(
                text = when {
                    trip.expenses.isEmpty() -> "Nothing to settle yet."
                    trip.payments.isEmpty() -> "Nobody owes anybody. Everyone is square."
                    else -> "All squared up — every repayment is accounted for."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MoneySlate
            )
        } else {
            Text(
                "The fewest payments that clear what's left. Tap Mark paid once the " +
                    "money has actually changed hands.",
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
            Spacer(Modifier.height(12.dp))
            moves.forEach { t ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onRecordPayment(t.fromId, t.toId, t.amountMinor) }
                    ) {
                        Text("Mark paid")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { onRecordPayment(null, null, null) }) {
            Text("Record a different payment")
        }
        Spacer(Modifier.height(28.dp))
    }
}

/* -------------------------------------------------------------- all trips */

/**
 * Every trip ever entered. Finished trips stay exactly as they were — starting a
 * new one never touches an old one, and you can reopen last year's to check who
 * paid for the boat.
 */
@Composable
private fun TripsPane(
    library: Library,
    onOpenTrip: (String) -> Unit,
    onNewTrip: () -> Unit,
    onRestore: (Library) -> Pair<Int, Int>
) {
    Column {
        Text("All trips", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap one to open it. Nothing is ever cleared out to make room for a " +
                "new trip.",
            style = MaterialTheme.typography.bodyMedium,
            color = MoneySlate
        )
        Spacer(Modifier.height(18.dp))

        library.byNewest.forEach { t ->
            val isOpen = t.id == library.active?.id
            val outstanding = t.outstandingCount
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenTrip(t.id) }
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isOpen) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Currently open",
                                tint = MoneyGold,
                                modifier = Modifier.width(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = if (t.name.isBlank()) "Untitled trip" else t.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isOpen) MoneyGold else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = Backup.dateLabel(t.createdAt) + " · " + t.people.size + " people · " +
                            t.expenses.size + (if (t.expenses.size == 1) " expense" else " expenses"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MoneySlate
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = Money.withCode(t.totalMinor, t.homeCurrency),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when {
                            t.expenses.isEmpty() -> "nothing logged"
                            outstanding == 0 -> "all square"
                            outstanding == 1 -> "1 payment left"
                            else -> "$outstanding payments left"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (outstanding == 0) MoneySlate else MoneyOwed
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onNewTrip) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start a new trip")
        }
        Text(
            "Starts blank but keeps the same people, since the group usually is " +
                "the same. Edit them in trip settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MoneySlate,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(30.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(24.dp))
        BackupSection(library, onRestore)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Sending a copy off the tablet is the only thing that survives losing it. A
 * backup holds every trip, and restoring merges rather than replaces, so it can
 * never cost you a trip that wasn't in the file.
 */
@Composable
private fun BackupSection(
    library: Library,
    onRestore: (Library) -> Pair<Int, Int>
) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Library?>(null) }
    var failed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val loaded = Backup.readLibrary(ctx, uri)
            if (loaded == null) failed = true else pending = loaded
        }
    }

    val candidate = pending
    if (candidate != null) {
        val existingIds = library.trips.map { it.id }.toSet()
        val replaced = candidate.trips.count { existingIds.contains(it.id) }
        val added = candidate.trips.size - replaced
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore from this backup?") },
            text = {
                Text(
                    buildString {
                        if (added > 0) {
                            append("Adds ").append(added)
                            append(if (added == 1) " trip" else " trips")
                        }
                        if (added > 0 && replaced > 0) append(", and ")
                        if (replaced > 0) {
                            append("overwrites ").append(replaced)
                            append(if (replaced == 1) " trip you already have" else " trips you already have")
                        }
                        append(".\n\nEvery other trip on this tablet is left alone.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    result = onRestore(candidate)
                    pending = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }

    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            title = { Text("That isn't a Trip Split backup") },
            text = { Text("Nothing was changed. Pick the .json file the app sent you.") },
            confirmButton = { TextButton(onClick = { failed = false }) { Text("OK") } }
        )
    }

    val done = result
    if (done != null) {
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("Restored") },
            text = {
                Text(
                    done.first.toString() + " added, " + done.second.toString() +
                        " overwritten. They're in the list above."
                )
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } }
        )
    }

    Text("Keeping a copy", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Everything is written to this tablet the moment you save it, so a crash " +
            "loses nothing. Losing the tablet does. A backup holds every trip — " +
            "send one to yourself now and again, wherever you like.",
        style = MaterialTheme.typography.bodyMedium,
        color = MoneySlate
    )
    Spacer(Modifier.height(14.dp))
    Row {
        Button(onClick = { ctx.startActivity(Backup.shareIntent(ctx, library)) }) {
            Text("Send a backup")
        }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text("Restore")
        }
    }
}
