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
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.StudyTimeSummary
import com.ogenrwotdaniel.wakeproof.data.WallDay
import com.ogenrwotdaniel.wakeproof.distraction.BEDTIME_END_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.CHROME_DAILY_CAP_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.CHROME_OPEN_END_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.CHROME_OPEN_START_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.DEADLINE_WORDS_SHOWN
import com.ogenrwotdaniel.wakeproof.distraction.SLEEP_END_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.SLEEP_START_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.WallRule
import com.ogenrwotdaniel.wakeproof.distraction.WallRules
import com.ogenrwotdaniel.wakeproof.distraction.WindowNotificationGuard
import com.ogenrwotdaniel.wakeproof.distraction.gatedOpenWindow
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
    val studyTime by viewModel.studyTime.collectAsStateWithLifecycle()
    val studyPackages by viewModel.studyPackages.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val reportUi by viewModel.reportUi.collectAsStateWithLifecycle()
    var limitDialogFor by remember { mutableStateOf<InstalledApp?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }
    val wallRules by viewModel.wallRules.collectAsStateWithLifecycle()
    val wallWeek by viewModel.wallWeek.collectAsStateWithLifecycle()
    val otherProfileApps by viewModel.otherProfileApps.collectAsStateWithLifecycle()
    val dndAccess by viewModel.dndAccess.collectAsStateWithLifecycle()
    val notificationAccess by viewModel.notificationAccess.collectAsStateWithLifecycle()
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
                            }
                            TextButton(
                                onClick = {
                                    limitDialogFor = InstalledApp(capped.packageName, capped.label)
                                }
                            ) { Text("Tighten") }
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
                        text = "🔒 Limits are fixed once set: they can be tightened at once " +
                            "but never raised, softened or removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (otherProfileApps.isNotEmpty()) {
                        OtherProfileWarning(otherProfileApps)
                    }
                    WallRulesSection(
                        rules = wallRules,
                        dndAccess = dndAccess,
                        notificationAccess = notificationAccess,
                        onGrantDnd = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            )
                        },
                        onGrantNotifications = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                    .putExtra(
                                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                        WindowNotificationGuard.component(context).flattenToString()
                                    )
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
 * Slider dialog for one app's daily cap. Only stricter caps can be picked: the
 * slider stops at the current minutes, Nudge is shut once the cap blocks, and
 * there is no Remove — see [DistractionLimitRepository.requestLimit].
 */
@Composable
private fun LimitDialog(
    app: InstalledApp,
    currentLimit: AppLimit?,
    canEnforceBlocks: Boolean,
    onSet: (Int, LimitMode) -> Unit,
    onDismiss: () -> Unit
) {
    val maxMinutes = (currentLimit?.minutes ?: MAX_LIMIT_MINUTES).coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)
    var minutes by remember { mutableFloatStateOf(minOf(currentLimit?.minutes ?: 30, maxMinutes).toFloat()) }
    var mode by remember { mutableStateOf(currentLimit?.mode ?: LimitMode.NUDGE) }
    val loosening = DistractionLimitRepository.isLoosening(currentLimit, AppLimit(minutes.toInt(), mode))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily limit — ${app.label}") },
        text = {
            Column {
                Text("${minutes.toInt()} min per day.")
                // A cap already at the floor has nothing left to slide to (and an
                // empty range would throw).
                if (maxMinutes > MIN_LIMIT_MINUTES) {
                    Slider(
                        value = minutes,
                        onValueChange = { minutes = it },
                        valueRange = MIN_LIMIT_MINUTES.toFloat()..maxMinutes.toFloat()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == LimitMode.NUDGE,
                        onClick = { mode = LimitMode.NUDGE },
                        label = { Text("Nudge") },
                        // Block → Nudge is a loosening.
                        enabled = currentLimit?.mode != LimitMode.BLOCK
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
                Text(
                    text = "🔒 Permanent: once set, this limit can only be tightened. It can " +
                        "never be raised, switched back to Nudge or removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
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
            TextButton(onClick = { onSet(minutes.toInt(), mode) }, enabled = !loosening) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private const val MIN_LIMIT_MINUTES = 10
private const val MAX_LIMIT_MINUTES = 240

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
 * The walls over gated apps, read-only: every one is fixed in code
 * ([com.ogenrwotdaniel.wakeproof.distraction.WallRulesRepository]), so the rows
 * carry no switch and no Edit, only the grants a wall needs to work.
 */
@Composable
private fun WallRulesSection(
    rules: WallRules,
    dndAccess: Boolean,
    notificationAccess: Boolean,
    onGrantDnd: () -> Unit,
    onGrantNotifications: () -> Unit
) {
    val context = LocalContext.current
    Text(
        text = "Walls",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Browser gating is fixed on, so its row would say only what Chrome's below does.
    WallRule.entries.filter { it != WallRule.GATE_BROWSERS }.forEach { rule ->
        val value = rules.value(rule)
        Column(Modifier.fillMaxWidth()) {
            Text(text = "🔒 " + ruleTitle(rule, value, context), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = ruleSubtitle(rule, context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (rule == WallRule.OPEN_WINDOW_END_MINUTES) {
                gatedOpenText(rules, context)?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (rule == WallRule.SESSION_DND && !dndAccess) {
                TextButton(onClick = onGrantDnd) { Text("Allow Do Not Disturb access") }
            }
            if (rule == WallRule.OPEN_WINDOW_START_MINUTES && !notificationAccess) {
                TextButton(onClick = onGrantNotifications) {
                    Text("Allow notification access to hide walled apps' notifications")
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        fun clock(m: Int) = formatAlarmTime(context, m / 60, m % 60)
        Text(
            text = "🔒 Chrome: weekdays ${clock(CHROME_OPEN_START_MINUTES)}–${clock(CHROME_OPEN_END_MINUTES)}, " +
                "$CHROME_DAILY_CAP_MINUTES min a day",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Every other browser follows the gated schedule. On a weekday, a calendar event " +
                "titled $DEADLINE_WORDS_SHOWN keeps Chrome open until then, as late as " +
                "${clock(SLEEP_START_MINUTES)}. Blackboard on the home screen is open every day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    // Fixed in code (NightRules) like the rest.
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "🔒 Night wall ${formatAlarmTime(context, SLEEP_START_MINUTES / 60, SLEEP_START_MINUTES % 60)}" +
                "–${formatAlarmTime(context, SLEEP_END_MINUTES / 60, SLEEP_END_MINUTES % 60)}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Every app but calls, the home screen, Settings, Clock, Calendar, WakeProof and " +
                "a running focus session's apps is shut.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Text(
        text = "Every wall is fixed: none can be moved, loosened or turned off from the phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * What the open window really leaves once the bedtime wall (until 08:40) takes
 * its share, or null when the window is off. Without it a window ending before
 * 08:40 looks open and never is.
 */
private fun gatedOpenText(rules: WallRules, context: android.content.Context): String? {
    val (opens, closes) = gatedOpenWindow(rules) ?: return null
    val days = if (rules.weekendShut) "Monday to Thursday" else "every day"
    fun clock(m: Int) = formatAlarmTime(context, m / 60, m % 60)
    return if (opens >= closes) "Gated apps never open: the bedtime wall covers the whole window."
    else "Gated apps are open ${clock(opens)}–${clock(closes)}, $days."
}

private fun ruleTitle(rule: WallRule, value: Int, context: android.content.Context): String = when (rule) {
    WallRule.GATE_BROWSERS -> "Gate browsers too"
    WallRule.WEEKEND_SHUT -> "Shut Friday to Sunday"
    WallRule.BEDTIME_START_MINUTES ->
        if (value < 0) "Bedtime wall: off"
        else "Bedtime wall from ${formatAlarmTime(context, value / 60, value % 60)}"
    WallRule.SESSION_DND -> "Do Not Disturb in sessions"
    WallRule.OPEN_WINDOW_START_MINUTES ->
        if (value < 0) "Open window: off (open all day Mon–Thu)"
        else "Open window starts ${formatAlarmTime(context, value / 60, value % 60)}"
    WallRule.OPEN_WINDOW_END_MINUTES ->
        "Open window ends ${formatAlarmTime(context, value / 60, value % 60)}"
}

private fun ruleSubtitle(rule: WallRule, context: android.content.Context): String = when (rule) {
    WallRule.GATE_BROWSERS -> "Every browser but Chrome follows the same schedule as social apps."
    WallRule.WEEKEND_SHUT ->
        "Gated apps stay completely shut from Friday through Sunday, reopening Monday."
    WallRule.BEDTIME_START_MINUTES ->
        "Gated apps shut until ${formatAlarmTime(context, BEDTIME_END_MINUTES / 60, BEDTIME_END_MINUTES % 60)} the next morning."
    WallRule.SESSION_DND -> "Only what your Do Not Disturb settings allow gets through."
    WallRule.OPEN_WINDOW_START_MINUTES ->
        "Monday to Thursday gated apps open at this time; outside the window their notifications are removed."
    WallRule.OPEN_WINDOW_END_MINUTES ->
        "Inside the Monday–Thursday window gated apps open directly with no countdown wait."
}

/** Gated apps WakeProof cannot see, because they live in another profile. */
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
                    "can't see that copy, so it bypasses the schedule and focus walls. " +
                    "Uninstall it there.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** The last 7 days in gated apps, today last. */
@Composable
private fun WallWeekCard(week: List<WallDay>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Gated apps — last 7 days", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${formatMinutes(week.sumOf { it.waitingAppMinutes })} across gated apps",
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
                        text = formatMinutes(day.waitingAppMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
