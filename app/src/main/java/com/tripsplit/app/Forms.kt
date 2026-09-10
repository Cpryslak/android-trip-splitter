package com.tripsplit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.UUID

private fun newId(): String = UUID.randomUUID().toString().take(8)

/* ------------------------------------------------------------- savers
 * Every field on these forms survives rotation and the app being killed in the
 * background while someone checks a receipt in another app. Lists and maps need
 * a hand to get into a Bundle. */

private val StringListSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { saved -> mutableStateListOf<String>().apply { addAll(saved) } }
)

private val StringMapSaver = mapSaver(
    save = { map: SnapshotStateMap<String, String> -> map.toMap() },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.forEach { (k, v) -> if (v is String) put(k, v) }
        }
    }
)

/** id, name, id, name … */
private val PeopleSaver = listSaver<SnapshotStateList<Pair<String, String>>, String>(
    save = { list -> list.flatMap { listOf(it.first, it.second) } },
    restore = { flat ->
        mutableStateListOf<Pair<String, String>>().apply {
            var i = 0
            while (i + 1 < flat.size) {
                add(flat[i] to flat[i + 1])
                i += 2
            }
        }
    }
)

/* ------------------------------------------------------------- shared pieces */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormTopBar(
    title: String,
    onBack: (() -> Unit)?,
    closeIcon: Boolean = false,
    onDelete: (() -> Unit)? = null
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    if (closeIcon) Icon(Icons.Default.Close, contentDescription = "Cancel")
                    else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    )
}

/** Which currency an amount is being typed in, when the trip has two. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencyToggle(trip: Trip, inLocal: Boolean, onChange: (Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !inLocal,
            onClick = { onChange(false) },
            label = { Text(trip.homeCurrency) }
        )
        FilterChip(
            selected = inLocal,
            onClick = { onChange(true) },
            label = { Text(trip.localCurrency) }
        )
    }
}

/**
 * The conversion line under a local-currency amount. An entry being edited keeps
 * the rate it was recorded at, so fixing a typo in the note never rewrites the
 * home amount; moving it to today's rate is a deliberate tap, and reversible.
 */
@Composable
private fun RateLine(
    trip: Trip,
    homeMinor: Long?,
    rateInUse: Double,
    originalRate: Double?,
    onUseRate: (Double) -> Unit
) {
    Spacer(Modifier.height(10.dp))
    if (homeMinor != null) {
        Text(
            "= " + Money.withCode(homeMinor, trip.homeCurrency) + " at " + Money.formatRate(rateInUse),
            style = MaterialTheme.typography.bodyLarge,
            color = MoneyGold
        )
    }
    when {
        rateInUse != trip.rate -> {
            Text(
                "That's the rate this was entered at. The trip's rate is now " +
                    Money.formatRate(trip.rate) + ".",
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
            TextButton(onClick = { onUseRate(trip.rate) }) { Text("Use today's rate instead") }
        }
        originalRate != null && originalRate != trip.rate -> {
            Text(
                "Using today's rate. It was entered at " + Money.formatRate(originalRate) + ".",
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
            TextButton(onClick = { onUseRate(originalRate) }) { Text("Keep the original rate") }
        }
    }
}

/** The day an entry belongs to, with a picker for backdating last night's dinner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(createdAt: Long, onChange: (Long) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val day = Dates.dayOf(createdAt)
    val label = Dates.dayLabel(day).let {
        if (it == "Today" || it == "Yesterday") it + " · " + Dates.shortDate(createdAt) else it
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { open = true }) { Text("Change date") }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Dates.utcMillisOf(day)
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        onChange(Dates.onDay(createdAt, Dates.dayFromUtcMillis(picked)))
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/** Chips for people, each carrying their colour so the ledger reads the same way. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonChips(
    trip: Trip,
    isSelected: (String) -> Boolean,
    onClick: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        trip.people.forEach { p ->
            FilterChip(
                selected = isSelected(p.id),
                onClick = { onClick(p.id) },
                label = { Text(p.name) },
                leadingIcon = { ColorDot(personColor(trip, p.id)) }
            )
        }
    }
}

/* ------------------------------------------------------------------ setup */

@Composable
fun SetupScreen(
    trip: Trip,
    firstRun: Boolean,
    onSave: (Trip) -> Unit,
    onCancel: (() -> Unit)?,
    onDeleteTrip: ((String) -> Unit)?
) {
    var confirmDeleteTrip by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(trip.name) }
    var home by rememberSaveable { mutableStateOf(trip.homeCurrency) }
    var local by rememberSaveable { mutableStateOf(trip.localCurrency) }
    var rateText by rememberSaveable {
        mutableStateOf(if (trip.rate == 1.0) "" else Money.formatRate(trip.rate))
    }
    val people = rememberSaveable(saver = PeopleSaver) {
        mutableStateListOf<Pair<String, String>>().also { list ->
            trip.people.forEach { list.add(it.id to it.name) }
            while (list.size < 4) list.add(newId() to "")
        }
    }

    val named = people.filter { it.second.isNotBlank() }
    // Two people with the same name are two separate balances that print
    // identically, which is how a settle-up ends up reading "Chris pays Chris".
    val duplicateNames = named
        .groupBy { it.second.trim().lowercase() }
        .filter { it.value.size > 1 }
        .keys
    val canSave = named.size >= 2 && duplicateNames.isEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
        topBar = {
            // The very first run has nowhere to go back to, so no button at all.
            FormTopBar(
                title = if (firstRun) "New trip" else "Trip settings",
                onBack = onCancel,
                closeIcon = firstRun
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
            if (firstRun) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Log what people pay for as you go. At the end this works out " +
                        "the shortest list of payments that squares everyone up.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MoneySlate
                )
                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Trip name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(26.dp))
            SectionLabel("Who's on the trip")
            people.forEachIndexed { i, entry ->
                val referenced = trip.isReferenced(entry.first)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isDuplicate = entry.second.isNotBlank() &&
                        duplicateNames.contains(entry.second.trim().lowercase())
                    ColorDot(personColorAt(i), 14.dp)
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = entry.second,
                        onValueChange = { people[i] = entry.first to it },
                        label = { Text(if (isDuplicate) "Name — already used" else "Name") },
                        isError = isDuplicate,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = { if (!referenced) people.removeAt(i) },
                        enabled = !referenced && people.size > 2
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = if (referenced)
                                "Already has expenses, can't be removed" else "Remove"
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { people.add(newId() to "") },
                enabled = people.size < 12
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add someone")
            }

            Spacer(Modifier.height(30.dp))
            SectionLabel("Money")
            OutlinedTextField(
                value = home,
                onValueChange = { home = it.uppercase().take(4) },
                label = { Text("Home currency") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Add a second currency if you'll be spending abroad. Everything is " +
                    "recorded in your home currency using the rate at the time, so " +
                    "changing the rate later won't rewrite what you already logged.",
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = local,
                    onValueChange = { local = it.uppercase().take(4) },
                    label = { Text("Local") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { rateText = it },
                    label = { Text("1 " + (if (local.isBlank()) "local" else local) + " = ? " + home) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.3f)
                )
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    val rate = Money.parseRate(rateText) ?: 1.0
                    onSave(
                        trip.copy(
                            name = name.trim(),
                            homeCurrency = if (home.isBlank()) "USD" else home.trim(),
                            localCurrency = local.trim(),
                            rate = rate,
                            people = named.map { Person(it.first, it.second.trim()) },
                            started = true
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (firstRun) "Start the trip" else "Save", style = MaterialTheme.typography.labelLarge)
            }
            if (!canSave) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (duplicateNames.isNotEmpty())
                        "Two people share a name. Everyone needs a distinct one, or the " +
                            "settle-up can't tell whose balance is whose — add a surname " +
                            "or an initial. Renaming is safe: expenses stay attached to " +
                            "the right person."
                    else "Two names at least.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (duplicateNames.isNotEmpty()) MoneyOwed else MoneySlate
                )
            }
            if (!firstRun && onDeleteTrip != null) {
                Spacer(Modifier.height(38.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(22.dp))
                Text("Delete this trip", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Removes it and its " + trip.expenses.size +
                        (if (trip.expenses.size == 1) " expense" else " expenses") +
                        " from this tablet. Your other trips aren't touched. If you " +
                        "only want to stop using it, just leave it — old trips cost " +
                        "nothing to keep.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MoneySlate
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { confirmDeleteTrip = true }) {
                    Text("Delete trip")
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDeleteTrip && onDeleteTrip != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTrip = false },
            title = { Text("Delete this trip?") },
            text = {
                Text(
                    (if (trip.name.isBlank()) "This trip" else trip.name) +
                        " and everything logged against it will be removed. You'll get " +
                        "a few seconds to undo, and a backup can always bring it back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteTrip = false
                    onDeleteTrip(trip.id)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTrip = false }) { Text("Keep it") }
            }
        )
    }
}

/* --------------------------------------------------------------- expenses */

private enum class SplitMode { Equal, Shares, Exact }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditExpenseScreen(
    trip: Trip,
    existing: Expense?,
    onSave: (Expense) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit
) {
    // What was typed originally, in the currency it was typed in — unless the
    // trip has since lost its second currency, in which case the home figure.
    val typedOriginally: Long? = existing?.let {
        if (it.localMinor != null && trip.hasLocalCurrency) it.localMinor else it.homeMinor
    }
    var amountText by rememberSaveable {
        mutableStateOf(typedOriginally?.let { Money.format(it) } ?: "")
    }
    var inLocal by rememberSaveable {
        mutableStateOf(existing?.localMinor != null && trip.hasLocalCurrency)
    }
    var rateInUse by rememberSaveable { mutableStateOf(existing?.rateUsed ?: trip.rate) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "") }
    var payerId by rememberSaveable {
        mutableStateOf(existing?.payerId ?: trip.people.firstOrNull()?.id ?: "")
    }
    var createdAt by rememberSaveable {
        mutableStateOf(existing?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis())
    }
    val sharedBy = rememberSaveable(saver = StringListSaver) {
        mutableStateListOf<String>().also { list ->
            if (existing != null) list.addAll(existing.sharedBy)
            else trip.people.forEach { list.add(it.id) }
        }
    }

    // Weights that add up to the typed total are exact amounts; anything else
    // uneven is shares. Equal weights are just an equal split.
    val initialWeights: Map<String, Long>? = existing?.weights
    val initialMode = when {
        existing == null || initialWeights == null || !existing.isUneven -> SplitMode.Equal
        typedOriginally != null && initialWeights.values.sum() == typedOriginally -> SplitMode.Exact
        else -> SplitMode.Shares
    }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val sharesText = rememberSaveable(saver = StringMapSaver) {
        mutableStateMapOf<String, String>().also { m ->
            if (initialMode == SplitMode.Shares) {
                initialWeights?.forEach { (id, w) -> m[id] = w.toString() }
            }
        }
    }
    val exactText = rememberSaveable(saver = StringMapSaver) {
        mutableStateMapOf<String, String>().also { m ->
            if (initialMode == SplitMode.Exact) {
                initialWeights?.forEach { (id, w) -> m[id] = Money.format(w) }
            }
        }
    }

    val typedMinor = Money.parse(amountText)
    val usingLocal = inLocal && trip.hasLocalCurrency
    val homeMinor = when {
        typedMinor == null -> null
        usingLocal -> Money.convert(typedMinor, rateInUse)
        else -> typedMinor
    }
    val typedCode = if (usingLocal) trip.localCurrency else trip.homeCurrency

    // Participants in the order people are listed, so the rows below never jump.
    val participants = trip.people.map { it.id }.filter { sharedBy.contains(it) }
    val weights: Map<String, Long>? = when (mode) {
        SplitMode.Equal -> null
        SplitMode.Shares -> participants.associateWith {
            (sharesText[it]?.toLongOrNull() ?: 1L).coerceAtLeast(0L)
        }
        SplitMode.Exact -> participants.associateWith { Money.parse(exactText[it] ?: "") ?: 0L }
    }
    // Someone given nothing isn't sharing it; drop them so "split 3 ways" stays true.
    val savedParticipants = if (weights == null) participants
    else participants.filter { (weights[it] ?: 0L) > 0L }
    val savedWeights = weights?.filterKeys { savedParticipants.contains(it) }
    val assigned = weights?.values?.sum() ?: 0L
    val splitOk = when (mode) {
        SplitMode.Equal -> true
        SplitMode.Shares -> assigned > 0L
        SplitMode.Exact -> typedMinor != null && assigned == typedMinor
    }
    val valid = homeMinor != null && homeMinor > 0L && payerId.isNotBlank() &&
        savedParticipants.isNotEmpty() && splitOk
    val preview: Map<String, Long>? =
        if (homeMinor != null && savedParticipants.isNotEmpty() && splitOk)
            Settle.shares(homeMinor, savedParticipants, savedWeights) else null

    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (existing == null) amountFocus.requestFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
        topBar = {
            FormTopBar(
                title = if (existing == null) "New expense" else "Edit expense",
                onBack = onCancel,
                onDelete = if (existing != null) ({ onDelete(existing.id) }) else null
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
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().focusRequester(amountFocus)
            )

            if (trip.hasLocalCurrency) {
                Spacer(Modifier.height(12.dp))
                CurrencyToggle(trip, inLocal) { inLocal = it }
                if (usingLocal) {
                    RateLine(
                        trip = trip,
                        homeMinor = homeMinor,
                        rateInUse = rateInUse,
                        originalRate = existing?.rateUsed,
                        onUseRate = { rateInUse = it }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("What was it for") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Categories.all.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = if (category == c) "" else c },
                        label = { Text(c) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("When")
            DateRow(createdAt) { createdAt = it }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Paid by")
            PersonChips(trip, isSelected = { it == payerId }, onClick = { payerId = it })

            Spacer(Modifier.height(28.dp))
            SectionLabel("Split between")
            PersonChips(
                trip,
                isSelected = { sharedBy.contains(it) },
                onClick = { if (sharedBy.contains(it)) sharedBy.remove(it) else sharedBy.add(it) }
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = {
                    sharedBy.clear()
                    trip.people.forEach { sharedBy.add(it.id) }
                }) { Text("Everyone") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    sharedBy.clear()
                    if (payerId.isNotBlank()) sharedBy.add(payerId)
                }) { Text("Just the payer") }
            }

            if (participants.size > 1) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("How")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = mode == SplitMode.Equal,
                        onClick = { mode = SplitMode.Equal },
                        label = { Text("Equally") }
                    )
                    FilterChip(
                        selected = mode == SplitMode.Shares,
                        onClick = { mode = SplitMode.Shares },
                        label = { Text("By shares") }
                    )
                    FilterChip(
                        selected = mode == SplitMode.Exact,
                        onClick = { mode = SplitMode.Exact },
                        label = { Text("Exact amounts") }
                    )
                }

                when (mode) {
                    SplitMode.Equal -> {}
                    SplitMode.Shares -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "One share each is an even split. Give someone two if they " +
                                "had double, or none if they sat it out.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MoneySlate
                        )
                        Spacer(Modifier.height(6.dp))
                        participants.forEach { id ->
                            val count = (sharesText[id]?.toLongOrNull() ?: 1L).coerceAtLeast(0L)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(trip, id, 28.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    trip.nameOf(id),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                IconButton(
                                    onClick = { sharesText[id] = (count - 1L).coerceAtLeast(0L).toString() },
                                    enabled = count > 0L
                                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                                Text(
                                    count.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(30.dp)
                                )
                                IconButton(
                                    onClick = { sharesText[id] = (count + 1L).toString() }
                                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    preview?.get(id)?.let { Money.format(it) } ?: "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (count > 0L) MaterialTheme.colorScheme.onBackground else MoneySlate,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                        }
                    }
                    SplitMode.Exact -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Type each person's amount in " + typedCode + ". They have to " +
                                "add up to the total.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MoneySlate
                        )
                        Spacer(Modifier.height(6.dp))
                        participants.forEach { id ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(trip, id, 28.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    trip.nameOf(id),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                OutlinedTextField(
                                    value = exactText[id] ?: "",
                                    onValueChange = { exactText[id] = it },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    label = { Text(typedCode) },
                                    modifier = Modifier.width(160.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (typedMinor == null) {
                            Text(
                                "Enter the total first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MoneySlate
                            )
                        } else {
                            val left = typedMinor - assigned
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = when {
                                        left == 0L -> "Adds up to " + Money.format(typedMinor) + "."
                                        left > 0L -> Money.format(left) + " still to assign."
                                        else -> "Over by " + Money.format(-left) + "."
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = when {
                                        left == 0L -> MoneyGold
                                        left > 0L -> MoneySlate
                                        else -> MoneyOwed
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (left > 0L) {
                                    TextButton(onClick = {
                                        val blank = participants.filter {
                                            (Money.parse(exactText[it] ?: "") ?: 0L) == 0L
                                        }
                                        val targets = if (blank.isEmpty()) participants else blank
                                        Settle.shares(left, targets).forEach { (id, add) ->
                                            val current = Money.parse(exactText[id] ?: "") ?: 0L
                                            exactText[id] = Money.format(current + add)
                                        }
                                    }) { Text("Split the rest") }
                                }
                            }
                        }
                    }
                }
            }

            if (preview != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(14.dp))
                val values = preview.values.distinct().sorted()
                Text(
                    text = when {
                        values.size <= 1 ->
                            Money.withCode(values.firstOrNull() ?: 0L, trip.homeCurrency) + " each"
                        mode == SplitMode.Equal ->
                            Money.format(values.last()) + " for some, " +
                                Money.format(values.first()) +
                                " for others — the odd cents have to land somewhere"
                        else -> savedParticipants.joinToString(" · ") {
                            trip.nameOf(it) + " " + Money.format(preview[it] ?: 0L)
                        } + (if (usingLocal) " (" + trip.homeCurrency + ")" else "")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MoneySlate
                )
            }

            Spacer(Modifier.height(30.dp))
            Button(
                onClick = {
                    if (homeMinor != null) onSave(
                        Expense(
                            id = existing?.id ?: newId(),
                            note = note.trim(),
                            payerId = payerId,
                            sharedBy = savedParticipants,
                            homeMinor = homeMinor,
                            localMinor = if (usingLocal) typedMinor else null,
                            rateUsed = if (usingLocal) rateInUse else null,
                            createdAt = createdAt,
                            category = category,
                            weights = savedWeights
                        )
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/* --------------------------------------------------------------- payments */

/**
 * Records money handed from one person to another. Reached from Mark paid on a
 * settle-up line (prefilled with the exact figure), or from scratch for a
 * partial repayment.
 */
@Composable
fun PaymentScreen(
    trip: Trip,
    existing: Payment?,
    suggestedFromId: String?,
    suggestedToId: String?,
    suggestedMinor: Long?,
    onSave: (Payment) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit
) {
    var amountText by rememberSaveable {
        mutableStateOf(
            when {
                existing?.localMinor != null && trip.hasLocalCurrency -> Money.format(existing.localMinor)
                existing != null -> Money.format(existing.homeMinor)
                suggestedMinor != null -> Money.format(suggestedMinor)
                else -> ""
            }
        )
    }
    var inLocal by rememberSaveable {
        mutableStateOf(existing?.localMinor != null && trip.hasLocalCurrency)
    }
    var rateInUse by rememberSaveable { mutableStateOf(existing?.rateUsed ?: trip.rate) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var fromId by rememberSaveable { mutableStateOf(existing?.fromId ?: suggestedFromId ?: "") }
    var toId by rememberSaveable { mutableStateOf(existing?.toId ?: suggestedToId ?: "") }
    var createdAt by rememberSaveable {
        mutableStateOf(existing?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis())
    }

    val typedMinor = Money.parse(amountText)
    val usingLocal = inLocal && trip.hasLocalCurrency
    val homeMinor = when {
        typedMinor == null -> null
        usingLocal -> Money.convert(typedMinor, rateInUse)
        else -> typedMinor
    }
    val samePerson = fromId.isNotBlank() && fromId == toId
    val valid = homeMinor != null && homeMinor > 0L &&
        fromId.isNotBlank() && toId.isNotBlank() && !samePerson

    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (existing == null && suggestedMinor == null) amountFocus.requestFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
        topBar = {
            FormTopBar(
                title = if (existing == null) "Record a repayment" else "Edit repayment",
                onBack = onCancel,
                onDelete = if (existing != null) ({ onDelete(existing.id) }) else null
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
            Spacer(Modifier.height(8.dp))
            Text(
                "This isn't a trip cost and never gets split — it just moves money " +
                    "between two people.",
                style = MaterialTheme.typography.bodyMedium,
                color = MoneySlate
            )

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount handed over") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().focusRequester(amountFocus)
            )

            if (trip.hasLocalCurrency) {
                Spacer(Modifier.height(12.dp))
                CurrencyToggle(trip, inLocal) { inLocal = it }
                if (usingLocal) {
                    RateLine(
                        trip = trip,
                        homeMinor = homeMinor,
                        rateInUse = rateInUse,
                        originalRate = existing?.rateUsed,
                        onUseRate = { rateInUse = it }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("When")
            DateRow(createdAt) { createdAt = it }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Who paid")
            PersonChips(trip, isSelected = { it == fromId }, onClick = { fromId = it })

            Spacer(Modifier.height(24.dp))
            SectionLabel("Who received it")
            PersonChips(trip, isSelected = { it == toId }, onClick = { toId = it })
            if (samePerson) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Those are the same person.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MoneyOwed
                )
            }

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (cash, bank transfer…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(30.dp))
            Button(
                onClick = {
                    if (homeMinor != null) onSave(
                        Payment(
                            id = existing?.id ?: newId(),
                            fromId = fromId,
                            toId = toId,
                            homeMinor = homeMinor,
                            note = note.trim(),
                            localMinor = if (usingLocal) typedMinor else null,
                            rateUsed = if (usingLocal) rateInUse else null,
                            createdAt = createdAt
                        )
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
