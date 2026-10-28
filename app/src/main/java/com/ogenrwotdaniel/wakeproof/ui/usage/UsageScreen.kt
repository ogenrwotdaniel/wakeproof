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
import androidx.compose.material3.Switch
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
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.PendingLimitChange
import com.ogenrwotdaniel.wakeproof.data.StudyTimeSummary
import com.ogenrwotdaniel.wakeproof.data.WallDay
import com.ogenrwotdaniel.wakeproof.distraction.PendingRule
import com.ogenrwotdaniel.wakeproof.distraction.RULE_OFF
import com.ogenrwotdaniel.wakeproof.distraction.RULE_OFF_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.RULE_ON
import com.ogenrwotdaniel.wakeproof.distraction.WallRule
import com.ogenrwotdaniel.wakeproof.distraction.WallRules
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
    val studyTime by viewModel.studyTime.collectAsStateWithLifecycle()
    val studyPackages by viewModel.studyPackages.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val reportUi by viewModel.reportUi.collectAsStateWithLifecycle()
    var limitDialogFor by remember { mutableStateOf<InstalledApp?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }
    val wallRules by viewModel.wallRules.collectAsStateWithLifecycle()
    val wallPending by viewModel.wallPending.collectAsStateWithLifecycle()
    val wallWeek by viewModel.wallWeek.collectAsStateWithLifecycle()
    val otherProfileApps by viewModel.otherProfileApps.collectAsStateWithLifecycle()
    val dndAccess by viewModel.dndAccess.collectAsStateWithLifecycle()
    var editingRule by remember { mutableStateOf<WallRule?>(null) }
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

                    studyTime?.let { summary ->
                        StudyTimeCard(summary = summary, labelOf = viewModel::appLabel)
                    }

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
                                    text = (if (app.packageName in studyPackages) "📚 " else "") +
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
                    Text(
                        text = "Loosening or removing a limit waits a week. Tightening one " +
                            "applies at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (otherProfileApps.isNotEmpty()) {
                        OtherProfileWarning(otherProfileApps)
                    }
                    WallRulesSection(
                        rules = wallRules,
                        pending = wallPending,
                        dndAccess = dndAccess,
                        onToggle = { rule, on -> viewModel.requestRule(rule, if (on) RULE_ON else RULE_OFF) },
                        onEdit = { editingRule = it },
                        onKeep = viewModel::cancelRulePending,
                        onGrantDnd = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            )
                        }
                    )
                    if (wallWeek.isNotEmpty()) WallWeekCard(wallWeek)

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
            canEnforceBlocks = viewModel.canEnforceBlocks(),
            onSet = { minutes, mode ->
                viewModel.setLimit(app.packageName, minutes, mode)
                limitDialogFor = null
            },
            onDismiss = { limitDialogFor = null }
        )
    }

    editingRule?.let { rule ->
        RuleValueDialog(
            rule = rule,
            current = wallRules.value(rule),
            onSet = { value ->
                viewModel.requestRule(rule, value)
                editingRule = null
            },
            onDismiss = { editingRule = null }
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
 * "Becomes 60 min · Nudge on Thu 5 Nov 09:12" — when a queued loosening lands, in the
 * phone's own clock format.
 */
private fun pendingLabel(context: android.content.Context, change: PendingLimitChange): String {
    val what = change.limit?.let {
        "becomes ${it.minutes} min · ${it.mode.name.lowercase().replaceFirstChar(Char::uppercase)}"
    } ?: "limit removed"
    return "⌛ ${what.replaceFirstChar(Char::uppercase)} on ${landsAt(context, change.effectiveAtMillis)}"
}

/**
 * "Thu 5 Nov 09:12" for [millis], in the phone's own clock format. Carries the
 * date because a week-long wait makes a bare weekday ambiguous.
 */
private fun landsAt(context: android.content.Context, millis: Long): String {
    val at = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    val day = at.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
    return "$day ${formatAlarmTime(context, at.hour, at.minute)}"
}

/**
 * Slider dialog for one app's daily cap. Tightening applies now; loosening or
 * removing it is queued for a week — see [DistractionLimitRepository.requestLimit].
 */
@Composable
private fun LimitDialog(
    app: InstalledApp,
    currentLimit: AppLimit?,
    pending: PendingLimitChange?,
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
                                append("This loosens the limit, so it waits a week before it ")
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
                Text(if (loosening) "Set in 7 days" else "Set")
            }
        },
        dismissButton = {
            Row {
                if (currentLimit != null) {
                    TextButton(onClick = { onSet(null, mode) }) { Text("Remove in 7 days") }
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
 * Time in study apps today, credited by name, across every synced device. Mostly
 * so that an afternoon writing in Samsung Notes shows up as what it was rather
 * than as anonymous screen time. Focus time no screen measured (a block timed on
 * the watch, or on paper) is added as its own row, credited to the app focus is
 * spent in — see [com.ogenrwotdaniel.wakeproof.data.studyTimeSummary] for why it
 * never double counts.
 */
@Composable
private fun StudyTimeCard(
    summary: StudyTimeSummary,
    labelOf: (String) -> String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "📚 ${formatMinutes(summary.totalMinutes)} studying today",
                style = MaterialTheme.typography.titleMedium
            )
            summary.measured.forEach { app ->
                StudyRow(label = app.label, minutes = app.minutes)
            }
            val credited = summary.creditedPackage
            if (credited != null && summary.creditedMinutes > 0) {
                StudyRow(
                    label = "${labelOf(credited)} · focus timer",
                    minutes = summary.creditedMinutes
                )
            }
            if (summary.peers.isNotEmpty()) {
                val context = LocalContext.current
                Text(
                    text = "Includes " + summary.peers.joinToString { peer ->
                        val at = peerTime(peer.updatedAtMillis)
                        "${peer.deviceName} (as of ${formatAlarmTime(context, at.hour, at.minute)})"
                    } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (credited != null) {
                    "Focus-timer time no screen measured — a block on your watch, or on " +
                        "paper — counts as ${labelOf(credited)}. Choose which apps count in " +
                        "Settings → Study apps."
                } else {
                    "Choose which apps count in Settings → Study apps. A running focus " +
                        "timer counts toward your goal whichever app you study in."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StudyRow(label: String, minutes: Long) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = formatMinutes(minutes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun peerTime(millis: Long): java.time.LocalTime =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()

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

/**
 * The rules in front of the waiting apps. Same contract as a limit: switching
 * one on, or making it stricter, is immediate; anything looser waits a week,
 * and the row says when it lands.
 */
@Composable
private fun WallRulesSection(
    rules: WallRules,
    pending: Map<WallRule, PendingRule>,
    dndAccess: Boolean,
    onToggle: (WallRule, Boolean) -> Unit,
    onEdit: (WallRule) -> Unit,
    onKeep: (WallRule) -> Unit,
    onGrantDnd: () -> Unit
) {
    val context = LocalContext.current
    Text(
        text = "Walls",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    WallRule.entries.forEach { rule ->
        val value = rules.value(rule)
        val switchable = rule !in NUMBER_RULES
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = ruleTitle(rule, value, context), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = ruleSubtitle(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (rule == WallRule.SESSION_DND && value != RULE_OFF && !dndAccess) {
                    TextButton(onClick = onGrantDnd) { Text("Allow Do Not Disturb access") }
                }
                pending[rule]?.let { change ->
                    Text(
                        text = "⌛ ${pendingRuleText(rule, change.value, context)} on " +
                            landsAt(context, change.effectiveAtMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            if (rule in pending) TextButton(onClick = { onKeep(rule) }) { Text("Keep") }
            if (switchable) {
                Switch(checked = value != RULE_OFF, onCheckedChange = { onToggle(rule, it) })
            } else {
                TextButton(onClick = { onEdit(rule) }) { Text("Edit") }
            }
        }
    }
    Text(
        text = "Turning a wall off or loosening it waits a week. Tightening applies at once.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun ruleTitle(rule: WallRule, value: Int, context: android.content.Context): String = when (rule) {
    WallRule.GATE_BROWSERS -> "Browsers wait too"
    WallRule.ESCALATING_WAIT -> "Each wait today is longer"
    WallRule.DAILY_BUDGET_MINUTES ->
        if (value <= 0) "Daily budget: off" else "Daily budget: $value min"
    WallRule.EARN_THE_FEED -> "Earn the feed"
    WallRule.BEDTIME_START_MINUTES ->
        if (value < 0) "Bedtime wall: off"
        else "Bedtime wall from ${formatAlarmTime(context, value / 60, value % 60)}"
    WallRule.SESSION_DND -> "Do Not Disturb in sessions"
    WallRule.OPEN_WINDOW_START_MINUTES ->
        if (value < 0) "Open window: off (open all day)"
        else "Open window starts ${formatAlarmTime(context, value / 60, value % 60)}"
    WallRule.OPEN_WINDOW_END_MINUTES ->
        "Open window ends ${formatAlarmTime(context, value / 60, value % 60)}"
}

/** Rules that are a number, edited with a slider rather than switched. */
private val NUMBER_RULES = setOf(
    WallRule.DAILY_BUDGET_MINUTES,
    WallRule.BEDTIME_START_MINUTES,
    WallRule.OPEN_WINDOW_START_MINUTES,
    WallRule.OPEN_WINDOW_END_MINUTES
)

private fun ruleSubtitle(rule: WallRule): String = when (rule) {
    WallRule.GATE_BROWSERS -> "Chrome, Samsung Internet and the rest wait like social apps."
    WallRule.ESCALATING_WAIT -> "5 min, then 10, 15… up to 30 min for each visit today."
    WallRule.DAILY_BUDGET_MINUTES -> "Shared by every waiting app. Spent = shut until midnight."
    WallRule.EARN_THE_FEED -> "Waiting apps stay shut until today's first focus block."
    WallRule.BEDTIME_START_MINUTES -> "Waiting apps shut until your morning alarm is verified."
    WallRule.SESSION_DND -> "Only what your Do Not Disturb settings allow gets through."
    WallRule.OPEN_WINDOW_START_MINUTES ->
        "Outside the window every waiting app is shut, browsers and messaging included."
    WallRule.OPEN_WINDOW_END_MINUTES -> "Inside it the wait, the budget and the rest still apply."
}

private fun pendingRuleText(rule: WallRule, value: Int, context: android.content.Context): String =
    when (rule) {
        WallRule.DAILY_BUDGET_MINUTES -> if (value <= 0) "Budget off" else "Becomes $value min"
        WallRule.BEDTIME_START_MINUTES ->
            if (value < 0) "Bedtime wall off"
            else "Moves to ${formatAlarmTime(context, value / 60, value % 60)}"
        WallRule.OPEN_WINDOW_START_MINUTES ->
            if (value < 0) "Window off"
            else "Opens at ${formatAlarmTime(context, value / 60, value % 60)}"
        WallRule.OPEN_WINDOW_END_MINUTES ->
            "Closes at ${formatAlarmTime(context, value / 60, value % 60)}"
        else -> if (value == RULE_OFF) "Turns off" else "Turns on"
    }

/** Slider for the rules that are a number rather than a switch. */
@Composable
private fun RuleValueDialog(
    rule: WallRule,
    current: Int,
    onSet: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val budget = rule == WallRule.DAILY_BUDGET_MINUTES
    fun clock(minutes: Int) = formatAlarmTime(context, minutes / 60, minutes % 60)
    // Budget: 5–120 min in 5s. Times: quarter hours across the useful span.
    val range = when (rule) {
        WallRule.DAILY_BUDGET_MINUTES -> 5f..120f
        WallRule.BEDTIME_START_MINUTES -> (12 * 60f)..(23 * 60f + 45)
        WallRule.OPEN_WINDOW_START_MINUTES -> 0f..(23 * 60f + 30)
        else -> 15f..(23 * 60f + 45)
    }
    val step = if (budget) 5 else 15
    val fallback = when (rule) {
        WallRule.DAILY_BUDGET_MINUTES -> 25
        WallRule.BEDTIME_START_MINUTES -> 17 * 60
        else -> rule.default
    }
    val turnedOff = current < 0 || (budget && current == 0)
    var picked by remember {
        mutableFloatStateOf((if (turnedOff) fallback else current).toFloat().coerceIn(range))
    }
    val value = (picked / step).toInt() * step
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (rule) {
                    WallRule.DAILY_BUDGET_MINUTES -> "Daily budget"
                    WallRule.BEDTIME_START_MINUTES -> "Bedtime wall"
                    WallRule.OPEN_WINDOW_START_MINUTES -> "Open window starts"
                    else -> "Open window ends"
                }
            )
        },
        text = {
            Column {
                Text(
                    when (rule) {
                        WallRule.DAILY_BUDGET_MINUTES -> "$value min a day across every waiting app."
                        WallRule.BEDTIME_START_MINUTES ->
                            "From ${clock(value)} until your morning alarm is verified."
                        WallRule.OPEN_WINDOW_START_MINUTES -> "Waiting apps open at ${clock(value)}."
                        else -> "Waiting apps shut again at ${clock(value)}."
                    }
                )
                Slider(
                    value = picked,
                    onValueChange = { picked = it },
                    valueRange = range,
                    steps = ((range.endInclusive - range.start) / step).toInt() - 1
                )
                Text(
                    text = "Anything that leaves more time open waits a week. Less applies now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSet(value) }) { Text("Set") } },
        dismissButton = {
            Row {
                // The window's end has no "off": switching the window off is its start's job.
                if (rule != WallRule.OPEN_WINDOW_END_MINUTES) {
                    TextButton(onClick = { onSet(if (budget) 0 else RULE_OFF_MINUTES) }) {
                        Text("Turn off in 7 days")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Waiting apps WakeProof cannot see, because they live in another profile. */
@Composable
private fun OtherProfileWarning(apps: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Outside the walls",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = "${apps.joinToString()} ${if (apps.size == 1) "is" else "are"} also " +
                    "installed in Secure Folder, Dual Messenger or a work profile. WakeProof " +
                    "can't see that copy, so it has no wait, no budget and no focus wall. " +
                    "Uninstall it there.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** The last 7 days at the wall, today last. */
@Composable
private fun WallWeekCard(week: List<WallDay>) {
    val walked = week.sumOf { it.walkedAway }
    val opened = week.sumOf { it.opened }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "This week at the wall", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Walked away $walked · opened $opened · " +
                    "${formatMinutes(week.sumOf { it.waitingAppMinutes })} in waiting apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            week.forEach { day ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = chartDayLabel(day.epochDay, LocalDate.now().toEpochDay()),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "↩ ${day.walkedAway} · ✓ ${day.opened} · ${formatMinutes(day.waitingAppMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "↩ walked away from the wait · ✓ waited and opened",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
