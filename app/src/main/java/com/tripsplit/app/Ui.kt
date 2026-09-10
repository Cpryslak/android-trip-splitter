package com.tripsplit.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/* ------------------------------------------------------------------ screens */

sealed interface Screen {
    data object Ledger : Screen
    data object Setup : Screen
    data object NewTrip : Screen
    data class Edit(val expenseId: String?) : Screen
    data class PersonDetail(val personId: String) : Screen
    data class Pay(
        val paymentId: String?,
        val fromId: String? = null,
        val toId: String? = null,
        val amountMinor: Long? = null
    ) : Screen

    /** Shown, never pushed: the very first trip's setup, with nowhere to go back to. */
    data object FirstRun : Screen
}

/*
 * The screen stack survives rotation and the process being killed in the
 * background. Ids are short hex, so a pipe is a safe separator.
 */
private fun Screen.encode(): String = when (this) {
    Screen.Ledger, Screen.FirstRun -> "ledger"
    Screen.Setup -> "setup"
    Screen.NewTrip -> "newtrip"
    is Screen.Edit -> "edit|" + (expenseId ?: "")
    is Screen.PersonDetail -> "person|$personId"
    is Screen.Pay -> "pay|" + (paymentId ?: "") + "|" + (fromId ?: "") + "|" +
        (toId ?: "") + "|" + (amountMinor?.toString() ?: "")
}

private fun decodeScreen(text: String): Screen {
    val p = text.split('|')
    fun part(i: Int): String? = p.getOrNull(i)?.ifBlank { null }
    return when (p[0]) {
        "setup" -> Screen.Setup
        "newtrip" -> Screen.NewTrip
        "edit" -> Screen.Edit(part(1))
        "person" -> Screen.PersonDetail(part(1) ?: return Screen.Ledger)
        "pay" -> Screen.Pay(part(1), part(2), part(3), part(4)?.toLongOrNull())
        else -> Screen.Ledger
    }
}

private val ScreenStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved ->
        mutableStateListOf<Screen>().apply {
            saved.forEach { add(decodeScreen(it)) }
            if (isEmpty()) add(Screen.Ledger)
        }
    }
)

/**
 * A fresh trip, carrying over the people from the last one — the same group
 * tends to travel together, and the names are the tedious part to retype.
 */
private fun blankTrip(library: Library): Trip = Trip(
    id = java.util.UUID.randomUUID().toString().take(8),
    createdAt = System.currentTimeMillis(),
    homeCurrency = library.active?.homeCurrency ?: "USD",
    localCurrency = library.active?.localCurrency ?: "",
    rate = library.active?.rate ?: 1.0,
    people = library.active?.people ?: emptyList(),
    started = false
)

/** Which pane of the ledger screen is showing. Hoisted so leaving and coming back lands on the same one. */
private enum class Pane { Ledger, Balances, Trips }

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var library by remember { mutableStateOf(Store.load(ctx)) }
    val stack = rememberSaveable(saver = ScreenStackSaver) {
        mutableStateListOf<Screen>(Screen.Ledger)
    }
    var pane by rememberSaveable { mutableStateOf(Pane.Ledger) }
    var backupTick by remember { mutableIntStateOf(0) }
    var backupJob by remember { mutableStateOf<Job?>(null) }

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }

    /* A couple of seconds after the last change, copy everything to the linked
       folder. Rapid edits collapse into one write. */
    fun scheduleAutoBackup(lib: Library) {
        if (!AutoBackup.status(appCtx).linked) return
        backupJob?.cancel()
        backupJob = scope.launch {
            delay(2000)
            withContext(Dispatchers.IO) { AutoBackup.run(appCtx, lib) }
            backupTick++
        }
    }

    fun backupNow() {
        backupJob?.cancel()
        val lib = library
        scope.launch {
            val error = withContext(Dispatchers.IO) { AutoBackup.run(appCtx, lib) }
            backupTick++
            snackbar.showSnackbar(
                if (error == null) "Backed up." else "Backup failed: $error",
                duration = SnackbarDuration.Short
            )
        }
    }

    val commitLibrary: (Library) -> Unit = { next ->
        library = next
        if (!Store.save(ctx, next)) {
            scope.launch {
                snackbar.showSnackbar(
                    "Couldn't write to storage. That change may not be saved.",
                    duration = SnackbarDuration.Long
                )
            }
        }
        scheduleAutoBackup(next)
    }
    val commit: (Trip) -> Unit = { next -> commitLibrary(library.withTrip(next)) }

    /** Applies a removal and offers to put it back for a few seconds. */
    fun commitWithUndo(next: Library, message: String, restore: (Library) -> Library) {
        commitLibrary(next)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) commitLibrary(restore(library))
        }
    }

    val trip = library.active
    val needsSetup = trip == null || !trip.started
    val shown: Screen = if (needsSetup) Screen.FirstRun else stack.last()

    BackHandler(enabled = !needsSetup && stack.size > 1) { pop() }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220)))
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "screen"
            ) { s ->
                val t = trip
                if (s == Screen.FirstRun || t == null || !t.started) {
                    // No trips yet, or the only one was never finished being set up.
                    val draft = remember { t?.takeIf { !it.started } ?: blankTrip(library) }
                    SetupScreen(
                        trip = draft,
                        firstRun = true,
                        onSave = { commit(it); home() },
                        onCancel = null,
                        onDeleteTrip = null
                    )
                } else when (s) {
                    Screen.FirstRun -> {}

                    Screen.NewTrip -> {
                        val draft = remember { blankTrip(library) }
                        SetupScreen(
                            trip = draft,
                            firstRun = true,
                            onSave = { commit(it); pop(); pane = Pane.Ledger },
                            onCancel = { pop() },
                            onDeleteTrip = null
                        )
                    }

                    Screen.Setup -> SetupScreen(
                        trip = t,
                        firstRun = false,
                        onSave = { commit(it); pop() },
                        onCancel = { pop() },
                        onDeleteTrip = { id ->
                            val gone = library.tripOf(id)
                            commitWithUndo(
                                next = library.without(id),
                                message = (gone?.name?.ifBlank { null } ?: "Trip") + " deleted"
                            ) { lib -> if (gone == null) lib else lib.withTrip(gone) }
                            home()
                        }
                    )

                    is Screen.Edit -> EditExpenseScreen(
                        trip = t,
                        existing = t.expenses.firstOrNull { it.id == s.expenseId },
                        onSave = { updated ->
                            val others = t.expenses.filterNot { it.id == updated.id }
                            commit(t.copy(expenses = others + updated))
                            pop()
                        },
                        onDelete = { id ->
                            val gone = t.expenses.firstOrNull { it.id == id }
                            commitWithUndo(
                                next = library.withTrip(t.copy(expenses = t.expenses.filterNot { it.id == id })),
                                message = "Expense deleted"
                            ) { lib ->
                                val cur = lib.tripOf(t.id)
                                if (cur == null || gone == null) lib
                                else lib.updated(cur.copy(expenses = cur.expenses + gone))
                            }
                            pop()
                        },
                        onCancel = { pop() }
                    )

                    is Screen.Pay -> PaymentScreen(
                        trip = t,
                        existing = t.payments.firstOrNull { it.id == s.paymentId },
                        suggestedFromId = s.fromId,
                        suggestedToId = s.toId,
                        suggestedMinor = s.amountMinor,
                        onSave = { updated ->
                            val others = t.payments.filterNot { it.id == updated.id }
                            commit(t.copy(payments = others + updated))
                            pop()
                        },
                        onDelete = { id ->
                            val gone = t.payments.firstOrNull { it.id == id }
                            commitWithUndo(
                                next = library.withTrip(t.copy(payments = t.payments.filterNot { it.id == id })),
                                message = "Repayment deleted"
                            ) { lib ->
                                val cur = lib.tripOf(t.id)
                                if (cur == null || gone == null) lib
                                else lib.updated(cur.copy(payments = cur.payments + gone))
                            }
                            pop()
                        },
                        onCancel = { pop() }
                    )

                    is Screen.PersonDetail -> PersonScreen(
                        trip = t,
                        personId = s.personId,
                        onOpenExpense = { id -> push(Screen.Edit(id)) },
                        onOpenPayment = { id -> push(Screen.Pay(id)) },
                        onRecordPayment = { from, to, amount -> push(Screen.Pay(null, from, to, amount)) },
                        onBack = { pop() }
                    )

                    Screen.Ledger -> LedgerScreen(
                        library = library,
                        trip = t,
                        pane = pane,
                        onPane = { pane = it },
                        onAdd = { push(Screen.Edit(null)) },
                        onAddPayment = { push(Screen.Pay(null)) },
                        onOpenPerson = { id -> push(Screen.PersonDetail(id)) },
                        onOpenExpense = { id -> push(Screen.Edit(id)) },
                        onOpenPayment = { id -> push(Screen.Pay(id)) },
                        onRecordPayment = { from, to, amount -> push(Screen.Pay(null, from, to, amount)) },
                        onSetup = { push(Screen.Setup) },
                        onNewTrip = { push(Screen.NewTrip) },
                        onOpenTrip = { id -> commitLibrary(library.opening(id)) },
                        onRestore = { restored ->
                            val (merged, counts) = library.merge(restored)
                            commitLibrary(merged)
                            counts
                        },
                        backupTick = backupTick,
                        onBackupChanged = { backupTick++ },
                        onBackupNow = { backupNow() }
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------- ledger */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerScreen(
    library: Library,
    trip: Trip,
    pane: Pane,
    onPane: (Pane) -> Unit,
    onAdd: () -> Unit,
    onAddPayment: () -> Unit,
    onOpenPerson: (String) -> Unit,
    onOpenExpense: (String) -> Unit,
    onOpenPayment: (String) -> Unit,
    onRecordPayment: (String?, String?, Long?) -> Unit,
    onSetup: () -> Unit,
    onNewTrip: () -> Unit,
    onOpenTrip: (String) -> Unit,
    onRestore: (Library) -> Pair<Int, Int>,
    backupTick: Int,
    onBackupChanged: () -> Unit,
    onBackupNow: () -> Unit
) {
    val ctx = LocalContext.current
    var shareMenu by remember { mutableStateOf(false) }

    val shareText: () -> Unit = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, if (trip.name.isBlank()) "Trip" else trip.name)
            putExtra(Intent.EXTRA_TEXT, Settle.summary(trip))
        }
        ctx.startActivity(Intent.createChooser(intent, "Send settle-up"))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
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
                    Box {
                        IconButton(onClick = { shareMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Send")
                        }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Send settle-up as text") },
                                onClick = { shareMenu = false; shareText() }
                            )
                            DropdownMenuItem(
                                text = { Text("Send ledger as a spreadsheet") },
                                onClick = {
                                    shareMenu = false
                                    ctx.startActivity(Export.shareIntent(ctx, trip))
                                }
                            )
                        }
                    }
                    IconButton(onClick = onSetup) {
                        Icon(Icons.Default.Settings, contentDescription = "Trip settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (pane == Pane.Trips) onNewTrip() else onAdd() },
                containerColor = MoneyGold,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (pane == Pane.Trips) "New trip" else "Add expense") }
            )
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
                            onClick = { onPane(p) },
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
                                BalancesPane(trip, onOpenPerson, onRecordPayment, onAddPayment)
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
                        BalancesPane(trip, onOpenPerson, onRecordPayment, onAddPayment)
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
                                onPane(Pane.Ledger)
                            },
                            onNewTrip = onNewTrip,
                            onRestore = onRestore,
                            backupTick = backupTick,
                            onBackupChanged = onBackupChanged,
                            onBackupNow = onBackupNow
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

/** Expenses and repayments share one chronological ledger. */
private sealed interface Entry {
    val at: Long
    val key: String
    data class Exp(val e: Expense) : Entry {
        override val at: Long get() = e.createdAt
        override val key: String get() = "e" + e.id
    }
    data class Pay(val p: Payment) : Entry {
        override val at: Long get() = p.createdAt
        override val key: String get() = "p" + p.id
    }
}

private sealed interface LedgerRow {
    val key: String
    data class Day(val label: String, val spentMinor: Long, override val key: String) : LedgerRow
    data class Item(val entry: Entry) : LedgerRow {
        override val key: String get() = entry.key
    }
}

/** Newest first, grouped under the day it happened with that day's spending. */
private fun ledgerRows(trip: Trip): List<LedgerRow> {
    val entries: List<Entry> =
        (trip.expenses.map { Entry.Exp(it) } + trip.payments.map { Entry.Pay(it) })
            .sortedByDescending { it.at }
    fun dayOf(at: Long): LocalDate? = if (at > 0L) Dates.dayOf(at) else null

    val spentByDay = HashMap<LocalDate?, Long>()
    for (e in entries) if (e is Entry.Exp) {
        val d = dayOf(e.at)
        spentByDay[d] = (spentByDay[d] ?: 0L) + e.e.homeMinor
    }

    val today = LocalDate.now()
    val out = ArrayList<LedgerRow>()
    var current: LocalDate? = null
    var first = true
    for (e in entries) {
        val d = dayOf(e.at)
        if (first || d != current) {
            first = false
            current = d
            out.add(
                LedgerRow.Day(
                    label = if (d == null) "Undated" else Dates.dayLabel(d, today),
                    spentMinor = spentByDay[d] ?: 0L,
                    key = "day-" + (d?.toString() ?: "none")
                )
            )
        }
        out.add(LedgerRow.Item(e))
    }
    return out
}

@Composable
private fun LedgerList(
    trip: Trip,
    onOpenExpense: (String) -> Unit,
    onOpenPayment: (String) -> Unit
) {
    val rows = remember(trip) { ledgerRows(trip) }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopStart) {
            Column {
                Text("Nothing logged yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap Add expense whenever someone pays for something. Say who paid " +
                        "and who it covers, and the settle-up works itself out.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MoneySlate
                )
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = rows, key = { it.key }) { row ->
            when (row) {
                is LedgerRow.Day -> DayHeader(row)
                is LedgerRow.Item -> {
                    when (val entry = row.entry) {
                        is Entry.Exp -> ExpenseRow(trip, entry.e) { onOpenExpense(entry.e.id) }
                        is Entry.Pay -> PaymentRow(trip, entry.p) { onOpenPayment(entry.p.id) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LedgerRow.Day) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = day.label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MoneySlate,
            modifier = Modifier.weight(1f)
        )
        if (day.spentMinor > 0L) {
            Text(
                text = Money.format(day.spentMinor) + " spent",
                style = MaterialTheme.typography.labelMedium,
                color = MoneySlate
            )
        }
    }
}

@Composable
private fun ExpenseRow(trip: Trip, e: Expense, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Avatar(trip, e.payerId, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (e.note.isBlank()) "Expense" else e.note,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    e.category.ifBlank { null },
                    trip.nameOf(e.payerId) + " paid",
                    "split " + e.sharedBy.size +
                        (if (e.sharedBy.size == 1) " way" else " ways") +
                        (if (e.isUneven) ", unevenly" else "")
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
            if (e.sharedBy.size > 1) {
                Spacer(Modifier.height(6.dp))
                AvatarRow(trip, e.sharedBy, 18.dp)
            }
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(trip, p.fromId, 30.dp)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "paid",
                tint = MoneySlate
            )
            Avatar(trip, p.toId, 30.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = trip.nameOf(p.fromId) + " repaid " + trip.nameOf(p.toId),
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
            Text(Money.format(p.homeMinor), style = MaterialTheme.typography.titleMedium)
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

/* ----------------------------------------------------------------- balances */

@Composable
private fun BalancesPane(
    trip: Trip,
    onOpenPerson: (String) -> Unit,
    onRecordPayment: (String?, String?, Long?) -> Unit,
    onAddPayment: () -> Unit
) {
    val balances = remember(trip) { Settle.balances(trip) }
    val grouped = remember(trip) { Settle.debtsByPerson(trip) }
    val categories = remember(trip) { Settle.byCategory(trip) }
    val hasCategories = categories.isNotEmpty() &&
        !(categories.size == 1 && categories[0].first == Categories.UNCATEGORISED)

    Column {
        Text("Where everyone stands", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap a name for their full breakdown.",
            style = MaterialTheme.typography.bodyMedium,
            color = MoneySlate
        )
        Spacer(Modifier.height(12.dp))

        balances.forEach { b ->
            val net = b.netMinor
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPerson(b.personId) }
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(trip, b.personId, 36.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = trip.nameOf(b.personId),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Column(horizontalAlignment = Alignment.End) {
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
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MoneySlate
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        if (hasCategories) {
            Spacer(Modifier.height(26.dp))
            Text("Where the money went", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(26.dp))
        Text("Still to settle", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))

        if (grouped.isEmpty()) {
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
                "Each line counts only what passed between those two people, so " +
                    "everybody can check their own. Mark them off as the money moves.",
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
            Spacer(Modifier.height(8.dp))

            grouped.forEach { (fromId, debts) ->
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(trip, fromId, 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = trip.nameOf(fromId) + " owes",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = Money.withCode(
                            debts.sumOf { it.amountMinor },
                            trip.homeCurrency
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MoneyOwed
                    )
                }
                Text(
                    text = "in " + debts.size +
                        (if (debts.size == 1) " payment" else " separate payments"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MoneySlate
                )
                Spacer(Modifier.height(4.dp))
                debts.forEach { d -> DebtCard(trip, d, onRecordPayment) }
            }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onAddPayment) {
            Text(if (grouped.isEmpty()) "Record a repayment" else "Record a different repayment")
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * One payable line, with the working behind it available on a tap. Seeing that a
 * figure is "hotel 100.00 less your taxi 40.00" is what stops an argument.
 */
@Composable
private fun DebtCard(
    trip: Trip,
    debt: PairDebt,
    onRecordPayment: (String?, String?, Long?) -> Unit
) {
    var showWorking by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "to",
                tint = MoneySlate
            )
            Spacer(Modifier.width(4.dp))
            Avatar(trip, debt.toId, 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = trip.nameOf(debt.toId),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = Money.format(debt.amountMinor),
                style = MaterialTheme.typography.titleMedium,
                color = MoneyOwed
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onRecordPayment(debt.fromId, debt.toId, debt.amountMinor) }
            ) {
                Text("Mark paid")
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { showWorking = !showWorking }) {
                Text(if (showWorking) "Hide working" else "Why this much")
            }
        }
        AnimatedVisibility(visible = showWorking) {
            Column {
                Spacer(Modifier.height(2.dp))
                debt.lines.forEach { line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = line.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MoneySlate,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Adds to what they owe: red. Takes off it: gold. Same rule as everywhere.
                        Text(
                            text = (if (line.amountMinor > 0L) "+" else "") +
                                Money.format(line.amountMinor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (line.amountMinor > 0L) MoneyOwed else MoneyGold
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
    onRestore: (Library) -> Pair<Int, Int>,
    backupTick: Int,
    onBackupChanged: () -> Unit,
    onBackupNow: () -> Unit
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
            val first = t.firstEntryAt
            val dates = if (first != null) Dates.rangeLabel(first, t.lastEntryAt ?: first)
            else Dates.shortDate(t.createdAt)
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
                        text = dates + " · " + t.people.size + " people · " +
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
            "Starts blank but keeps the same people and currencies, since the group " +
                "usually is the same. Edit them in trip settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MoneySlate,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(30.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(24.dp))
        BackupSection(library, onRestore, backupTick, onBackupChanged, onBackupNow)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Sending a copy off the tablet is the only thing that survives losing it. A
 * backup holds every trip, and restoring merges rather than replaces, so it can
 * never cost you a trip that wasn't in the file.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupSection(
    library: Library,
    onRestore: (Library) -> Pair<Int, Int>,
    backupTick: Int,
    onBackupChanged: () -> Unit,
    onBackupNow: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val status = remember(backupTick) { AutoBackup.status(ctx) }

    var pending by remember { mutableStateOf<Library?>(null) }
    var failed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            AutoBackup.link(ctx, uri)
            onBackupChanged()
            onBackupNow()
        }
    }
    val fileSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = Backup.writeTo(ctx, uri, library)
            scope.launch {
                snackbar.showSnackbar(
                    if (ok) "Backup saved." else "Couldn't write the backup there."
                )
            }
        }
    }
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
            text = { Text("Nothing was changed. Pick the .json file the app wrote.") },
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
            "loses nothing. Losing the tablet does. A backup holds every trip.",
        style = MaterialTheme.typography.bodyMedium,
        color = MoneySlate
    )

    Spacer(Modifier.height(22.dp))
    SectionLabel("Automatic")
    if (!status.linked) {
        Text(
            "Pick a folder once — one in Google Drive is ideal — and a copy of " +
                "everything is written there a moment after each change. Drive " +
                "then carries it off the tablet by itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = MoneySlate
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { folderPicker.launch(null) }) { Text("Choose a folder") }
    } else {
        Text(
            "Backing up to " + status.folderLabel,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                status.lastError != null ->
                    "Last attempt " + Dates.timeLabel(status.lastTry) + " failed: " + status.lastError
                status.lastOk > 0L -> "Last backup " + Dates.timeLabel(status.lastOk)
                else -> "Waiting for the first change."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.lastError != null) MoneyOwed else MoneySlate
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedButton(onClick = onBackupNow) { Text("Back up now") }
            TextButton(onClick = { folderPicker.launch(null) }) { Text("Change folder") }
            TextButton(onClick = {
                AutoBackup.unlink(ctx)
                onBackupChanged()
            }) { Text("Stop") }
        }
    }

    Spacer(Modifier.height(22.dp))
    SectionLabel("By hand")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(onClick = { ctx.startActivity(Backup.shareIntent(ctx, library)) }) {
            Text("Send a backup")
        }
        OutlinedButton(onClick = { fileSaver.launch(Backup.suggestedFileName()) }) {
            Text("Save to a file")
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text("Restore")
        }
    }
}
