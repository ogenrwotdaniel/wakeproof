package com.ogenrwotdaniel.wakeproof.ui.usage

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.AppUsage
import com.ogenrwotdaniel.wakeproof.data.DayUsage
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phone-usage dashboard: today's screen time vs focus time, a 7-day screen
 * time chart, and today's top apps. Gated on the "usage access" special grant.
 */
@Composable
fun UsageScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UsageViewModel = hiltViewModel()
) {
    val hasAccess by viewModel.hasAccess.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val subjectMinutes by viewModel.subjectMinutes.collectAsStateWithLifecycle()
    val limits by viewModel.limits.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val reportUi by viewModel.reportUi.collectAsStateWithLifecycle()
    var limitDialogFor by remember { mutableStateOf<AppUsage?>(null) }
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Phone usage", style = MaterialTheme.typography.headlineMedium)

            if (!hasAccess) {
                Text(
                    text = "WakeProof needs usage access to show how you've been " +
                        "using your phone. Nothing leaves the device.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant usage access") }
            } else {
                snapshot?.let { snap ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = formatMinutes(snap.todayMinutes),
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "screen time today",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = focusSummary(today.focusMinutes, snap.todayMinutes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Text(
                        text = "Last 7 days",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UsageWeekChart(week = snap.week)

                    val subjectRows = subjectMinutes.filter { it.minutes > 0 }
                    if (subjectRows.isNotEmpty()) {
                        Text(
                            text = "Focus by subject — 7 days",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val maxSubject = subjectRows.maxOf { it.minutes }
                        subjectRows.forEach { row ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = row.subject ?: "Untagged",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = formatMinutes(row.minutes.toLong()),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { row.minutes.toFloat() / maxSubject },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Text(
                        text = "Top apps today",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val max = snap.topApps.maxOfOrNull { it.minutes } ?: 1
                    snap.topApps.forEach { app ->
                        val limit = limits[app.packageName]
                        val overLimit = limit != null && app.minutes >= limit.minutes
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { limitDialogFor = app }) {
                                    Text(
                                        when {
                                            limit == null -> "Set limit"
                                            limit.mode == LimitMode.BLOCK ->
                                                "🔒 ${limit.minutes}m cap"
                                            else -> "⏳ ${limit.minutes}m cap"
                                        }
                                    )
                                }
                                Text(
                                    text = formatMinutes(app.minutes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (overLimit) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LinearProgressIndicator(
                                progress = { app.minutes.toFloat() / max },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (overLimit) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (snap.topApps.isEmpty()) {
                        Text(
                            text = "No app usage recorded yet today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "Weekly coach report",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    report?.let { cached ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    text = cached.text,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Written ${LocalDate.ofEpochDay(cached.epochDay)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Button(
                        onClick = viewModel::generateReport,
                        enabled = !reportUi.generating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                reportUi.generating -> "Writing your report…"
                                report == null -> "Generate weekly report"
                                else -> "Refresh weekly report"
                            }
                        )
                    }
                    Text(
                        text = "Written on your phone from your own logs — no key, no network, " +
                            "nothing sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TextButton(onClick = onBack) { Text("Back") }
        }
    }

    limitDialogFor?.let { app ->
        LimitDialog(
            app = app,
            currentLimit = limits[app.packageName],
            canEnforceBlocks = viewModel.canEnforceBlocks(),
            onSet = { minutes, mode ->
                viewModel.setLimit(app.packageName, minutes, mode)
                limitDialogFor = null
            },
            onDismiss = { limitDialogFor = null }
        )
    }
}

/** Slider dialog for one app's daily cap; "Remove" clears it. */
@Composable
private fun LimitDialog(
    app: AppUsage,
    currentLimit: AppLimit?,
    canEnforceBlocks: Boolean,
    onSet: (Int?, LimitMode) -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableFloatStateOf((currentLimit?.minutes ?: 30).toFloat()) }
    var mode by remember { mutableStateOf(currentLimit?.mode ?: LimitMode.NUDGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily limit — ${app.label}") },
        text = {
            Column {
                Text("${minutes.toInt()} min per day.")
                Slider(
                    value = minutes,
                    onValueChange = { minutes = it },
                    valueRange = 10f..240f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == LimitMode.NUDGE,
                        onClick = { mode = LimitMode.NUDGE },
                        label = { Text("Nudge") }
                    )
                    FilterChip(
                        selected = mode == LimitMode.BLOCK,
                        onClick = { mode = LimitMode.BLOCK },
                        label = { Text("Block") },
                        enabled = canEnforceBlocks
                    )
                }
                Text(
                    text = when {
                        mode == LimitMode.NUDGE ->
                            "One notification when you cross it. The app keeps working."
                        else ->
                            "${app.label} stops opening once the ${minutes.toInt()} min are " +
                                "spent, until midnight. There is no override, no extra " +
                                "minutes — set this only if you mean it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mode == LimitMode.BLOCK) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (!canEnforceBlocks) {
                    Text(
                        text = "Blocking needs both usage access and “display over other " +
                            "apps” (Settings → Floating timer grants the second).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(minutes.toInt(), mode) }) { Text("Set") }
        },
        dismissButton = {
            Row {
                if (currentLimit != null) {
                    TextButton(onClick = { onSet(null, mode) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun formatMinutes(minutes: Long): String =
    if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "${minutes}m"

private fun focusSummary(focusMinutes: Int, screenMinutes: Long): String {
    if (focusMinutes <= 0) return "No focus sessions yet today — start a Pomodoro!"
    val percent = if (screenMinutes > 0) (focusMinutes * 100 / screenMinutes).toInt() else 100
    return "🎯 $focusMinutes min focused — $percent% of screen time was deep work"
}

/** Daily screen-time bars scaled to the busiest day; label = weekday letter. */
@Composable
private fun UsageWeekChart(week: List<DayUsage>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val max = (week.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            if (week.isEmpty()) return@Canvas
            val slot = size.width / week.size
            val barWidth = slot * 0.5f
            week.forEachIndexed { index, day ->
                val fraction = day.minutes.toFloat() / max
                val left = index * slot + (slot - barWidth) / 2f
                drawRect(
                    color = track,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height)
                )
                if (fraction > 0f) {
                    drawRect(
                        color = primary,
                        topLeft = Offset(left, size.height * (1f - fraction)),
                        size = Size(barWidth, size.height * fraction)
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            week.forEach { day ->
                val letter = DayOfWeek.from(LocalDate.ofEpochDay(day.epochDay))
                    .name.first().toString()
                Text(
                    text = letter,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
