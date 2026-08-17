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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.UUID

private fun newId(): String = UUID.randomUUID().toString().take(8)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MoneySlate
    )
    Spacer(Modifier.height(8.dp))
}

/* ------------------------------------------------------------------ setup */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    trip: Trip,
    firstRun: Boolean,
    onSave: (Trip) -> Unit,
    onCancel: (() -> Unit)?
) {
    var name by remember { mutableStateOf(trip.name) }
    var home by remember { mutableStateOf(trip.homeCurrency) }
    var local by remember { mutableStateOf(trip.localCurrency) }
    var rateText by remember {
        mutableStateOf(if (trip.rate == 1.0) "" else trip.rate.toString())
    }
    val people = remember {
        mutableStateListOf<Pair<String, String>>().also { list ->
            trip.people.forEach { list.add(it.id to it.name) }
            while (list.size < 4) list.add(newId() to "")
        }
    }

    val named = people.filter { it.second.isNotBlank() }
    val canSave = named.size >= 2

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(if (firstRun) "New trip" else "Trip settings") },
                navigationIcon = {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
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
                    OutlinedTextField(
                        value = entry.second,
                        onValueChange = { people[i] = entry.first to it },
                        label = { Text("Name") },
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
                    val rate = rateText.replace(",", ".").toDoubleOrNull() ?: 1.0
                    onSave(
                        trip.copy(
                            name = name.trim(),
                            homeCurrency = if (home.isBlank()) "USD" else home.trim(),
                            localCurrency = local.trim(),
                            rate = if (rate > 0.0) rate else 1.0,
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
                    "Two names at least.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MoneySlate
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/* --------------------------------------------------------------- expenses */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditExpenseScreen(
    trip: Trip,
    existing: Expense?,
    onSave: (Expense) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit
) {
    var amountText by remember {
        mutableStateOf(
            when {
                existing == null -> ""
                existing.localMinor != null -> Money.format(existing.localMinor)
                else -> Money.format(existing.homeMinor)
            }
        )
    }
    var inLocal by remember { mutableStateOf(existing?.localMinor != null) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var payerId by remember { mutableStateOf(existing?.payerId ?: trip.people.firstOrNull()?.id ?: "") }
    val sharedBy = remember {
        mutableStateListOf<String>().also { list ->
            if (existing != null) list.addAll(existing.sharedBy)
            else trip.people.forEach { list.add(it.id) }
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    val typedMinor = Money.parse(amountText)
    val homeMinor = when {
        typedMinor == null -> null
        inLocal && trip.hasLocalCurrency -> Money.convert(typedMinor, trip.rate)
        else -> typedMinor
    }
    val valid = homeMinor != null && homeMinor > 0L && payerId.isNotBlank() && sharedBy.isNotEmpty()

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this expense?") },
            text = { Text("It'll come straight out of everyone's balances.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(existing.id) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep it") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(if (existing == null) "New expense" else "Edit expense") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
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
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            if (trip.hasLocalCurrency) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !inLocal,
                        onClick = { inLocal = false },
                        label = { Text(trip.homeCurrency) }
                    )
                    FilterChip(
                        selected = inLocal,
                        onClick = { inLocal = true },
                        label = { Text(trip.localCurrency) }
                    )
                }
                if (inLocal && homeMinor != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "= " + Money.withCode(homeMinor, trip.homeCurrency) +
                            " at " + trip.rate,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MoneyGold
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

            Spacer(Modifier.height(28.dp))
            SectionLabel("Paid by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trip.people.forEach { p ->
                    FilterChip(
                        selected = payerId == p.id,
                        onClick = { payerId = p.id },
                        label = { Text(p.name) }
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("Split between")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trip.people.forEach { p ->
                    FilterChip(
                        selected = sharedBy.contains(p.id),
                        onClick = {
                            if (sharedBy.contains(p.id)) sharedBy.remove(p.id) else sharedBy.add(p.id)
                        },
                        label = { Text(p.name) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
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

            if (homeMinor != null && sharedBy.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(14.dp))
                val each = Settle.shares(homeMinor, sharedBy.toList())
                val values = each.values.distinct().sorted()
                Text(
                    text = if (values.size <= 1)
                        Money.withCode(values.firstOrNull() ?: 0L, trip.homeCurrency) + " each"
                    else
                        Money.format(values.last()) + " for some, " +
                            Money.format(values.first()) + " for others — the odd cents have to land somewhere",
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
                            sharedBy = sharedBy.toList(),
                            homeMinor = homeMinor,
                            localMinor = if (inLocal && trip.hasLocalCurrency) typedMinor else null,
                            rateUsed = if (inLocal && trip.hasLocalCurrency) trip.rate else null,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
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
