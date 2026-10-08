package com.ogenrwotdaniel.wakeproof.ui.usage

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.AppUsage
import com.ogenrwotdaniel.wakeproof.data.DayUsage
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.CoolingOff
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.PendingLimitChange
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import com.ogenrwotdaniel.wakeproof.ui.components.AppPickerDialog
import com.ogenrwotdaniel.wakeproof.ui.components.ChartBar
import com.ogenrwotdaniel.wakeproof.ui.components.LabeledBarChart
import com.ogenrwotdaniel.wakeproof.ui.components.chartDayLabel
import com.ogenrwotdaniel.wakeproof.ui.formatCompactDuration
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
    val limitedApps by viewModel.limitedApps.collectAsStateWithLifecycle()
    val pendingChanges by viewModel.pendingChanges.collectAsStateWithLifecycle()
    val coolingOff by viewModel.coolingOff.collectAsStateWithLifecycle()
    val studyApps by viewModel.studyApps.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val reportUi by viewModel.reportUi.collectAsStateWithLifecycle()
    var limitDialogFor by remember { mutableStateOf<InstalledApp?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }
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

                    StudyTimeCard(studyApps = studyApps)

                    Text(
                        text = "Screen time — last 7 days",
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
                                    text = (if (studyApps.any { it.packageName == app.packageName }) "📚 " else "") +
                                        app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        limitDialogFor = InstalledApp(app.packageName, app.label)
                                    }
                                ) {
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

                    // Separate from the top-apps list on purpose: a cap on something
                    // barely used today would never appear there, which reads as the
                    // limit not having saved.
                    Text(
                        text = "Daily limits",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    limitedApps.forEach { capped ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = (if (capped.limit.mode == LimitMode.BLOCK) "🔒 " else "⏳ ") +
                                        capped.label,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${capped.usedMinutes} of ${capped.limit.minutes} min" +
                                        if (capped.usedMinutes >= capped.limit.minutes) {
                                            if (capped.limit.mode == LimitMode.BLOCK) " — blocked until midnight"
                                            else " — over"
                                        } else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (capped.usedMinutes >= capped.limit.minutes)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                pendingChanges[capped.packageName]?.let { change ->
                                    Text(
                                        text = pendingLabel(context, change),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            if (capped.packageName in pendingChanges) {
                                TextButton(
                                    onClick = { viewModel.cancelPendingLimit(capped.packageName) }
                                ) { Text("Keep") }
                            }
                            TextButton(
                                onClick = {
                                    limitDialogFor = InstalledApp(capped.packageName, capped.label)
                                }
                            ) { Text("Edit") }
                        }
                    }
                    if (limitedApps.isEmpty()) {
                        Text(
                            text = "No limits set yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.loadInstalledApps()
                            showAppPicker = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Limit another app…") }
                    CoolingOffPicker(
                        coolingOff = coolingOff,
                        onPick = viewModel::setCoolingOffHours
                    )

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
            pending = pendingChanges[app.packageName],
            coolingOffHours = coolingOff.hours,
            canEnforceBlocks = viewModel.canEnforceBlocks(),
            onSet = { minutes, mode ->
                viewModel.setLimit(app.packageName, minutes, mode)
                limitDialogFor = null
            },
            onDismiss = { limitDialogFor = null }
        )
    }

    if (showAppPicker) {
        // Any installed app, not just today's top list — capping something
        // before it eats a day is the point.
        AppPickerDialog(
            title = "Limit an app",
            apps = installedApps,
            marked = limits.keys,
            markLabel = "(capped)",
            onPick = { app ->
                showAppPicker = false
                limitDialogFor = app
            },
            onDismiss = { showAppPicker = false }
        )
    }
}

/**
 * How long any loosening of a limit waits. Lengthening applies at once;
 * shortening is queued behind the current wait (see
 * [DistractionLimitRepository.requestCoolingOffHours]), and says when it lands.
 */
@Composable
private fun CoolingOffPicker(coolingOff: CoolingOff, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    Column {
        Text(
            text = "Wait before loosening or removing a limit",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DistractionLimitRepository.COOLING_OFF_CHOICES_HOURS.forEach { hours ->
                FilterChip(
                    selected = coolingOff.hours == hours,
                    onClick = { onPick(hours) },
                    label = { Text("$hours h") }
                )
            }
        }
        val queued = coolingOff.pendingHours
        Text(
            text = if (queued != null) {
                "⌛ Becomes $queued h on ${landsAt(context, coolingOff.pendingAtMillis)}. " +
                    "Shortening the wait has to sit out the current one first."
            } else {
                "A longer wait applies at once. A shorter one waits out the current wait first."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (queued != null) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * "Becomes 60 min · Nudge on Thu 09:12" — when a queued loosening lands, in the
 * phone's own clock format.
 */
private fun pendingLabel(context: android.content.Context, change: PendingLimitChange): String {
    val what = change.limit?.let {
        "becomes ${it.minutes} min · ${it.mode.name.lowercase().replaceFirstChar(Char::uppercase)}"
    } ?: "limit removed"
    return "⌛ ${what.replaceFirstChar(Char::uppercase)} on ${landsAt(context, change.effectiveAtMillis)}"
}

/** "Thu 09:12" for [millis], in the phone's own clock format. */
private fun landsAt(context: android.content.Context, millis: Long): String {
    val at = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    val day = at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    return "$day ${formatAlarmTime(context, at.hour, at.minute)}"
}

/**
 * Slider dialog for one app's daily cap. Tightening applies now; loosening or
 * removing it is queued for [coolingOffHours] — see [DistractionLimitRepository.requestLimit].
 */
@Composable
private fun LimitDialog(
    app: InstalledApp,
    currentLimit: AppLimit?,
    pending: PendingLimitChange?,
    coolingOffHours: Int,
    canEnforceBlocks: Boolean,
    onSet: (Int?, LimitMode) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                val loosening = DistractionLimitRepository.isLoosening(
                    currentLimit, AppLimit(minutes.toInt(), mode)
                )
                if (loosening || pending != null) {
                    Text(
                        text = buildString {
                            if (loosening) {
                                append("This loosens the limit, so it waits $coolingOffHours hours before it ")
                                append("takes effect. Until then the current ")
                                append("${currentLimit?.minutes} min cap keeps applying. ")
                                append("Tightening a limit always applies at once.")
                            }
                            if (pending != null) {
                                if (loosening) append("\n\n")
                                append("Already queued: ${pendingLabel(context, pending)}.")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
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
            val loosening = DistractionLimitRepository.isLoosening(
                currentLimit, AppLimit(minutes.toInt(), mode)
            )
            TextButton(onClick = { onSet(minutes.toInt(), mode) }) {
                Text(if (loosening) "Set in $coolingOffHours h" else "Set")
            }
        },
        dismissButton = {
            Row {
                if (currentLimit != null) {
                    TextButton(onClick = { onSet(null, mode) }) { Text("Remove in $coolingOffHours h") }
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

/**
 * Time in study apps today, credited by name. Mostly so that an afternoon
 * writing in Samsung Notes shows up as what it was rather than as anonymous
 * screen time; the focus streak itself is driven by the Pomodoro timer, which
 * keeps counting whichever app is on screen.
 */
@Composable
private fun StudyTimeCard(
    studyApps: List<UsageViewModel.StudyAppMinutes>,
    modifier: Modifier = Modifier
) {
    val total = studyApps.sumOf { it.minutes }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "📚 ${formatMinutes(total)} in study apps today",
                style = MaterialTheme.typography.titleMedium
            )
            studyApps.filter { it.minutes > 0 }.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = app.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = formatMinutes(app.minutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "Choose which apps count in Settings → Study apps. A running focus " +
                    "timer counts toward your goal whichever app you study in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Daily screen time, each bar labelled with its own total; today in bold. */
@Composable
private fun UsageWeekChart(week: List<DayUsage>, modifier: Modifier = Modifier) {
    val today = LocalDate.now().toEpochDay()
    LabeledBarChart(
        bars = week.map { day ->
            ChartBar(
                label = chartDayLabel(day.epochDay, today),
                value = day.minutes.toFloat(),
                valueLabel = if (day.minutes > 0) formatCompactDuration(day.minutes.toInt()) else "",
                emphasized = day.epochDay == today,
                reached = true
            )
        },
        modifier = modifier
    )
}
