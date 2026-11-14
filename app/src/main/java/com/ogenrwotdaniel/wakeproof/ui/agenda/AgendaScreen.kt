package com.ogenrwotdaniel.wakeproof.ui.agenda

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.ogenrwotdaniel.wakeproof.agenda.EventFocusDialog
import com.ogenrwotdaniel.wakeproof.agenda.eventFocusOffer
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Agenda: upcoming calendar events (next 7 days, read from the device
 * calendar provider that Google Calendar syncs into) with a local tick-off
 * checkbox per event instance. Gated on the READ_CALENDAR runtime permission.
 *
 * Like the home-screen agenda (2026-11-04): the checkbox ticks, and tapping a
 * TITLE offers a focus session that ends with the event — for what can be
 * focused for ([eventFocusOffer]); anything else the title tap ticks.
 */
@Composable
fun AgendaScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgendaViewModel = hiltViewModel()
) {
    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirming by remember { mutableStateOf<Pair<AgendaEvent, Int>?>(null) }
    confirming?.let { (event, minutes) ->
        EventFocusDialog(
            title = event.title,
            minutes = minutes,
            onConfirm = {
                confirming = null
                if (!viewModel.focusForEvent(event)) {
                    Toast.makeText(context, "Too little of it is left to focus for.", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirming = null }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Agenda", style = MaterialTheme.typography.headlineMedium)

            if (!hasPermission) {
                Text(
                    text = "Euler's Lab needs calendar access to list your upcoming " +
                        "events (lectures, labs, deadlines) so you can tick them " +
                        "off. Events are read on-device only.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant calendar access") }
            } else {
                val doneCount = items.count { it.done }
                Text(
                    text = if (items.isEmpty()) {
                        "No events in the next 7 days."
                    } else {
                        "$doneCount of ${items.size} done"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AgendaList(
                    items = items,
                    onToggle = viewModel::setDone,
                    onTitle = { item ->
                        val minutes = eventFocusOffer(
                            item.event, item.done, timer.active, System.currentTimeMillis()
                        )
                        if (minutes == null) viewModel.setDone(item, !item.done)
                        else confirming = item.event to minutes
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun AgendaList(
    items: List<AgendaItem>,
    onToggle: (AgendaItem, Boolean) -> Unit,
    onTitle: (AgendaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val grouped = items.groupBy {
        Instant.ofEpochMilli(it.event.beginMillis).atZone(zone).toLocalDate()
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        grouped.forEach { (date, dayItems) ->
            item(key = "header-$date") {
                Text(
                    text = dayLabel(date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(dayItems, key = { it.event.key }) { item ->
                AgendaRow(item = item, onToggle = { onToggle(item, it) }, onTitle = { onTitle(item) })
            }
        }
    }
}

@Composable
private fun AgendaRow(
    item: AgendaItem,
    onToggle: (Boolean) -> Unit,
    onTitle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = item.done, onCheckedChange = onToggle)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTitle)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = item.event.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = timeLabel(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun dayLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().plusDays(1) -> "Tomorrow"
    else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
}

private fun timeLabel(item: AgendaItem): String {
    if (item.event.allDay) return appendCalendar("All day", item)
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    val begin = Instant.ofEpochMilli(item.event.beginMillis).atZone(zone).format(fmt)
    val end = Instant.ofEpochMilli(item.event.endMillis).atZone(zone).format(fmt)
    return appendCalendar("$begin – $end", item)
}

private fun appendCalendar(time: String, item: AgendaItem): String =
    if (item.event.calendarName.isBlank()) time else "$time · ${item.event.calendarName}"