package com.ogenrwotdaniel.wakeproof.ui.usage

import com.ogenrwotdaniel.wakeproof.data.NightScreen
import com.ogenrwotdaniel.wakeproof.data.SLEEP_GRACE_MINUTES
import com.ogenrwotdaniel.wakeproof.data.neglectedSubject
import com.ogenrwotdaniel.wakeproof.data.nightOnTime
import com.ogenrwotdaniel.wakeproof.data.sleepScore
import com.ogenrwotdaniel.wakeproof.data.subjectBalance
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DayUsage
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.StudyTimeSummary
import com.ogenrwotdaniel.wakeproof.data.UsageSnapshot
import com.ogenrwotdaniel.wakeproof.data.WallDay
import com.ogenrwotdaniel.wakeproof.distraction.BEDTIME_END_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.MINUTES_PER_DAY
import com.ogenrwotdaniel.wakeproof.distraction.longNightDevice
import com.ogenrwotdaniel.wakeproof.distraction.nightWallEnd
import com.ogenrwotdaniel.wakeproof.distraction.nightWallStart
import com.ogenrwotdaniel.wakeproof.distraction.WallRules
import com.ogenrwotdaniel.wakeproof.distraction.WindowNotificationGuard
import com.ogenrwotdaniel.wakeproof.distraction.gatedOpenWindow
import com.ogenrwotdaniel.wakeproof.ui.components.AppPickerDialog
import com.ogenrwotdaniel.wakeproof.ui.components.ChartBar
import com.ogenrwotdaniel.wakeproof.ui.components.LabeledBarChart
import com.ogenrwotdaniel.wakeproof.ui.components.chartDayLabel
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import com.ogenrwotdaniel.wakeproof.ui.formatCompactDuration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Screen time: today's study time against the rest, the week, today's apps and
 * their caps, and the day's walls drawn on one 24-hour strip. Gated on the
 * "usage access" special grant. Laid out as cards, one subject each, with no
 * lock marks (2026-11-11, the user's request): everything here is fixed and
 * the user knows it.
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
    val subjectCredits by viewModel.subjectCredits.collectAsStateWithLifecycle()
    val nights by viewModel.nights.collectAsStateWithLifecycle()
    val limits by viewModel.limits.collectAsStateWithLifecycle()
    val limitedApps by viewModel.limitedApps.collectAsStateWithLifecycle()
    val studyTime by viewModel.studyTime.collectAsStateWithLifecycle()
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
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Screen time", style = MaterialTheme.typography.headlineMedium)

            if (!hasAccess) {
                Text(
                    text = "Euler's Lab needs usage access to show how you've been " +
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
                    TodayCard(snapshot = snap, study = studyTime, focusMinutes = today.focusMinutes)

                    studyTime?.let { summary ->
                        StudyTimeCard(summary = summary, labelOf = viewModel::appLabel)
                    }

                    SectionCard(title = "Other screen time", caption = "Last 7 days") {
                        UsageWeekChart(week = snap.week)
                    }

                    val subjectRows = subjectMinutes.filter { it.minutes > 0 }
                    if (subjectRows.isNotEmpty()) {
                        SectionCard(title = "Focus by subject", caption = "Last 7 days") {
                            val maxSubject = subjectRows.maxOf { it.minutes }
                            subjectRows.forEach { row ->
                                MeterRow(
                                    label = row.subject ?: "Untagged",
                                    value = formatMinutes(row.minutes.toLong()),
                                    fraction = row.minutes.toFloat() / maxSubject
                                )
                            }
                            // Time against credit weight, when credits are set in Settings.
                            val shares = subjectBalance(
                                subjectRows.mapNotNull { row -> row.subject?.let { it to row.minutes } }.toMap(),
                                subjectCredits
                            )
                            neglectedSubject(shares)?.let { behind ->
                                Text(
                                    text = "${behind.subject} is falling behind: " +
                                        "${(behind.timeShare * 100).toInt()}% of the time for " +
                                        "${(behind.creditShare * 100).toInt()}% of your credits.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    SectionCard(title = "Apps today", caption = "Outside your study apps") {
                        val max = snap.topApps.maxOfOrNull { it.minutes } ?: 1
                        snap.topApps.forEach { app ->
                            val limit = limits[app.packageName]
                            val overLimit = limit != null && app.minutes >= limit.minutes
                            MeterRow(
                                label = app.label,
                                value = formatMinutes(app.minutes),
                                fraction = app.minutes.toFloat() / max,
                                color = if (overLimit) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                action = if (limit == null) "Set limit" else "${limit.minutes}m cap",
                                onAction = { limitDialogFor = InstalledApp(app.packageName, app.label) }
                            )
                        }
                        if (snap.topApps.isEmpty()) {
                            Text(
                                text = "No app usage recorded yet today.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Separate from the apps list on purpose: a cap on something
                    // barely used today would never appear there, which reads as the
                    // limit not having saved.
                    SectionCard(title = "Daily limits") {
                        limitedApps.forEach { capped ->
                            val spent = capped.usedMinutes >= capped.limit.minutes
                            MeterRow(
                                label = capped.label,
                                value = "${capped.usedMinutes} of ${capped.limit.minutes} min",
                                fraction = capped.usedMinutes.toFloat() / capped.limit.minutes.coerceAtLeast(1),
                                color = if (spent) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                note = when {
                                    spent && capped.limit.mode == LimitMode.BLOCK -> "Shut until midnight"
                                    spent -> "Over"
                                    capped.limit.mode == LimitMode.BLOCK -> "Shuts when spent"
                                    else -> "Reminder only"
                                },
                                action = "Tighten",
                                onAction = { limitDialogFor = InstalledApp(capped.packageName, capped.label) }
                            )
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
                        ) { Text("Limit another app") }
                    }

                    if (otherProfileApps.isNotEmpty()) {
                        OtherProfileWarning(otherProfileApps)
                    }
                    ScheduleCard(
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

                    if (nights.any { it.minutes != null }) SleepCard(nights)

                    SectionCard(title = "Weekly report") {
                        report?.let { cached ->
                            Text(text = cached.text, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Written ${LocalDate.ofEpochDay(cached.epochDay)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = viewModel::generateReport,
                            enabled = !reportUi.generating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    reportUi.generating -> "Writing your report…"
                                    report == null -> "Write this week's report"
                                    else -> "Refresh the report"
                                }
                            )
                        }
                        Text(
                            text = "Written on your phone from your own logs. Nothing is sent anywhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

/** One subject, one card: a title, an optional quiet caption beside it, then the content. */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                caption?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

/** A rounded bar filled to [fraction]. */
@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                // NaN (0 of 0) and overshoot both land on a drawable width.
                .fillMaxWidth(if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** A name, its figure and a bar; optionally a quiet [note] and one text [action]. */
@Composable
private fun MeterRow(
    label: String,
    value: String,
    fraction: Float,
    color: Color = MaterialTheme.colorScheme.primary,
    note: String? = null,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Meter(fraction = fraction, color = color)
        if (note != null || action != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                action?.let { TextButton(onClick = onAction) { Text(it) } }
            }
        }
    }
}

/**
 * Today in two figures: study, and everything else. WakeProof's own screens
 * are named underneath and counted as neither; see [UsageSnapshot].
 */
@Composable
private fun TodayCard(snapshot: UsageSnapshot, study: StudyTimeSummary?, focusMinutes: Int) {
    val studyMinutes = study?.totalMinutes ?: 0L
    val other = snapshot.todayMinutes
    val studyColor = MaterialTheme.colorScheme.primary
    val otherColor = MaterialTheme.colorScheme.tertiary
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat(label = "Study", value = formatMinutes(studyMinutes), color = studyColor, modifier = Modifier.weight(1f))
                Stat(label = "Other screen time", value = formatMinutes(other), color = otherColor, modifier = Modifier.weight(1f))
            }
            if (studyMinutes + other > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (studyMinutes > 0) Box(Modifier.weight(studyMinutes.toFloat()).fillMaxHeight().background(studyColor))
                    if (other > 0) Box(Modifier.weight(other.toFloat()).fillMaxHeight().background(otherColor))
                }
            }
            Text(
                text = if (focusMinutes > 0) "$focusMinutes min of focus sessions today."
                else "No focus session yet today.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Euler's Lab itself (the home screen, the timer) was on screen " +
                    "${formatMinutes(snapshot.ownMinutes)} and counts as neither. A focus session " +
                    "counts once, whichever device ran it and whatever the phone showed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text = value, style = MaterialTheme.typography.displaySmall, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    text = "Permanent: once set, this limit can only be tightened. It can " +
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

/**
 * Time in study apps today, by name, across every synced device. Focus time no
 * screen measured (a block timed on the watch, or on paper) is added as its own
 * row, credited to the app focus is spent in — see
 * [com.ogenrwotdaniel.wakeproof.data.studyTimeSummary] for why it never double counts.
 */
@Composable
private fun StudyTimeCard(
    summary: StudyTimeSummary,
    labelOf: (String) -> String,
    modifier: Modifier = Modifier
) {
    SectionCard(title = "Study", caption = formatMinutes(summary.totalMinutes), modifier = modifier) {
        val total = summary.totalMinutes.coerceAtLeast(1).toFloat()
        summary.measured.forEach { app ->
            MeterRow(label = app.label, value = formatMinutes(app.minutes), fraction = app.minutes / total)
        }
        val credited = summary.creditedPackage
        if (credited != null && summary.creditedMinutes > 0) {
            MeterRow(
                label = "${labelOf(credited)} · focus timer",
                value = formatMinutes(summary.creditedMinutes),
                fraction = summary.creditedMinutes / total,
                color = MaterialTheme.colorScheme.secondary
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
                "Focus time no screen measured (a session on your watch, or on paper) " +
                    "counts as ${labelOf(credited)}. Study apps are chosen in Settings."
            } else {
                "Study apps are chosen in Settings."
            },
            style = MaterialTheme.typography.bodySmall,
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
 * The day's walls, read-only: every one is fixed in code
 * ([com.ogenrwotdaniel.wakeproof.distraction.WallRulesRepository]). Two 24-hour
 * strips, one for gated apps and one for everything else, then the same hours
 * in words and the grants a wall needs to work.
 */
@Composable
private fun ScheduleCard(
    rules: WallRules,
    dndAccess: Boolean,
    notificationAccess: Boolean,
    onGrantDnd: () -> Unit,
    onGrantNotifications: () -> Unit
) {
    val context = LocalContext.current
    fun clock(m: Int) = formatAlarmTime(context, m / 60, m % 60)
    val open = gatedOpenWindow(rules)?.takeIf { it.first < it.second }
    val openColor = MaterialTheme.colorScheme.primary
    val shutColor = MaterialTheme.colorScheme.surfaceVariant
    val nightColor = MaterialTheme.colorScheme.tertiary
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    // The phone's night wall is the longer one; the tablet keeps the sleep wall's hours.
    val phone = remember { longNightDevice(context) }
    val nightStart = nightWallStart(phone)
    val nightEnd = nightWallEnd(phone)

    SectionCard(title = "Schedule", caption = "Every day's walls") {
        DayStrip(
            label = "Gated apps",
            base = shutColor,
            spans = listOfNotNull(open?.let { Span(it.first, it.second, openColor) }),
            nowMinute = now
        )
        DayStrip(
            label = "Everything else",
            base = openColor.copy(alpha = 0.35f),
            spans = listOf(
                Span(nightStart, MINUTES_PER_DAY, nightColor),
                Span(0, nightEnd, nightColor)
            ),
            nowMinute = now
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6 * 60, 12 * 60, 18 * 60, MINUTES_PER_DAY - 1).forEach { minute ->
                Text(
                    text = if (minute == MINUTES_PER_DAY - 1) clock(0) else clock(minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ScheduleRow(
            swatch = openColor,
            label = "Gated apps open",
            value = when {
                open == null -> "Never"
                rules.weekendShut -> "Sat–Sun ${clock(open.first)}–${clock(open.second)}"
                else -> "${clock(open.first)}–${clock(open.second)}"
            }
        )
        if (rules.weekendShut) ScheduleRow(swatch = shutColor, label = "Monday to Friday", value = "Shut all day")
        if (rules.bedtimeStartMinutes >= 0) {
            ScheduleRow(
                swatch = shutColor,
                label = "Bedtime wall",
                value = "${clock(rules.bedtimeStartMinutes)}–${clock(BEDTIME_END_MINUTES)}"
            )
        }
        ScheduleRow(
            swatch = nightColor,
            label = "Night wall",
            value = "${clock(nightStart)}–${clock(nightEnd)}"
        )
        Text(
            text = "Gated apps are social, video, messaging, the Play Store and every browser. " +
                "The night wall shuts every app but calls, " + (if (phone) "the camera, " else "") +
                "Clock, Calendar, Euler's Lab and a running focus session's apps. In a focus session Do Not Disturb is on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (rules.sessionDnd && !dndAccess) {
            OutlinedButton(onClick = onGrantDnd, modifier = Modifier.fillMaxWidth()) {
                Text("Allow Do Not Disturb access")
            }
        }
        if (!notificationAccess) {
            OutlinedButton(onClick = onGrantNotifications, modifier = Modifier.fillMaxWidth()) {
                Text("Allow notification access")
            }
            Text(
                text = "Needed to remove walled apps' notifications while they are shut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Minutes of the day `[from, to)` drawn in [color] on a [DayStrip]. */
private data class Span(val from: Int, val to: Int, val color: Color)

/** One 24-hour strip: [base] all day, [spans] over it, and a mark at [nowMinute]. */
@Composable
private fun DayStrip(label: String, base: Color, spans: List<Span>, nowMinute: Int) {
    val mark = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(color = base, cornerRadius = radius)
            spans.forEach { span ->
                val left = size.width * span.from / MINUTES_PER_DAY
                // Never thinner than can be seen: half an hour is 2 % of the strip.
                val width = (size.width * (span.to - span.from) / MINUTES_PER_DAY).coerceAtLeast(6.dp.toPx())
                drawRoundRect(
                    color = span.color,
                    topLeft = Offset(left.coerceAtMost(size.width - width), 0f),
                    size = Size(width, size.height),
                    cornerRadius = radius
                )
            }
            val x = size.width * nowMinute / MINUTES_PER_DAY
            drawLine(color = mark, start = Offset(x, -3.dp.toPx()), end = Offset(x, size.height + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun ScheduleRow(swatch: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(swatch))
        Spacer(Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/** Gated apps WakeProof cannot see, because they live in another profile. */
@Composable
private fun OtherProfileWarning(apps: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Outside the walls",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "${apps.joinToString()} ${if (apps.size == 1) "is" else "are"} also " +
                    "installed in Secure Folder, Dual Messenger or a work profile. Euler's Lab " +
                    "can't see that copy, so it bypasses the schedule and focus walls. " +
                    "Uninstall it there.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** The last 7 days in gated apps, today last. */
@Composable
private fun WallWeekCard(week: List<WallDay>) {
    val today = LocalDate.now().toEpochDay()
    SectionCard(
        title = "Gated apps",
        caption = "${formatMinutes(week.sumOf { it.waitingAppMinutes })} in 7 days"
    ) {
        LabeledBarChart(
            bars = week.map { day ->
                ChartBar(
                    label = chartDayLabel(day.epochDay, today),
                    value = day.waitingAppMinutes.toFloat(),
                    valueLabel = if (day.waitingAppMinutes > 0) formatCompactDuration(day.waitingAppMinutes.toInt()) else "",
                    emphasized = day.epochDay == today,
                    reached = true
                )
            },
            height = 112.dp
        )
    }
}

/**
 * Sleep consistency: on how many of the last nights the phone was down by the
 * night wall's start, and how long the screen was on inside it each night.
 */
@Composable
private fun SleepCard(nights: List<NightScreen>) {
    val score = sleepScore(nights)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    SectionCard(
        title = "Sleep",
        caption = "Phone down by 20:00 on ${score.onTime} of ${score.measured} nights"
    ) {
        nights.forEach { night ->
            val day = java.time.LocalDate.ofEpochDay(night.epochDay).dayOfWeek
                .getDisplayName(java.time.format.TextStyle.SHORT, locale)
            Text(
                text = when {
                    night.minutes == null -> "$day  not measured"
                    nightOnTime(night) -> "$day  down on time ✓"
                    else -> "$day  screen on ${formatMinutes(night.minutes.toLong())} after 20:00"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (night.minutes != null && !nightOnTime(night)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "Counted from 20:00 to 03:00; up to $SLEEP_GRACE_MINUTES minutes of screen is still on time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
