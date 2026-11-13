package com.ogenrwotdaniel.wakeproof.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ogenrwotdaniel.wakeproof.distraction.WindowNotificationGuard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.DayOutcome
import com.ogenrwotdaniel.wakeproof.data.DayStat
import com.ogenrwotdaniel.wakeproof.ui.components.ChartBar
import com.ogenrwotdaniel.wakeproof.ui.components.LabeledBarChart
import com.ogenrwotdaniel.wakeproof.ui.components.chartDayLabel
import com.ogenrwotdaniel.wakeproof.ui.formatCompactDuration
import com.ogenrwotdaniel.wakeproof.ui.formatDurationMinutes
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimer
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimerRules
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * Dashboard: focus streak, today's goal cards, 7-day focus chart,
 * and navigation. A day counts toward the streak when every alarm due that day
 * was verified — the targets come from the schedule, not from a constant.
 */
@Composable
fun HomeScreen(
    onOpenOnboarding: () -> Unit,
    onOpenPomodoro: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenAgenda: () -> Unit,
    onOpenFlashcards: () -> Unit,
    onOpenForest: () -> Unit,
    onOpenSummaries: () -> Unit,
    onOpenTopThree: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val nextPushup by viewModel.nextPushupAlarm.collectAsStateWithLifecycle()
    val nextMath by viewModel.nextMathAlarm.collectAsStateWithLifecycle()
    val focusGoal by viewModel.focusGoalMinutes.collectAsStateWithLifecycle()
    val goalToday by viewModel.focusGoal.collectAsStateWithLifecycle()
    val exams by viewModel.exams.collectAsStateWithLifecycle()
    val ringsAlarms by viewModel.ringsAlarms.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val watchTimer by viewModel.watchTimer.collectAsStateWithLifecycle()
    val missingGrants by viewModel.missingGrants.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshGrants()
        onPauseOrDispose { }
    }
    var addingExam by remember { mutableStateOf(false) }

    val activity = LocalActivity.current
    LaunchedEffect(streak.days) {
        if (activity != null && viewModel.claimReviewPrompt(streak.days)) launchPlayReview(activity)
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
            Text(text = "WakeProof", style = MaterialTheme.typography.displaySmall)
            Text(
                text = "🔥 ${streak.days}-day focus streak" +
                    if (streak.best > streak.days) "  ·  best ${streak.best}" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // A finished session still owes its summary: said where the app
            // opens too, because the walls are up and Back from the timer lands here.
            val summaryState by viewModel.summary.collectAsStateWithLifecycle()
            val summaryNow = com.ogenrwotdaniel.wakeproof.ui.pomodoro.rememberSummaryNow(summaryState)
            val summaryContext = androidx.compose.ui.platform.LocalContext.current
            val bootCount = androidx.compose.runtime.remember {
                com.ogenrwotdaniel.wakeproof.study.steadyClock(summaryContext).bootCount
            }
            if (com.ogenrwotdaniel.wakeproof.study.summaryDue(
                    summaryState, summaryNow,
                    com.ogenrwotdaniel.wakeproof.study.SteadyClock(android.os.SystemClock.elapsedRealtime(), bootCount)
                ) != null
            ) {
                androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Session summary owed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Write down everything you studied, then photograph the page. " +
                                "Your apps stay shut until the photo is kept.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onOpenPomodoro, modifier = Modifier.fillMaxWidth()) {
                            Text("Open the focus timer")
                        }
                    }
                }
            }

            TopThreeCards(
                all = viewModel.topThrees.collectAsStateWithLifecycle().value,
                // Asked where the alarm rings; the list itself syncs and shows everywhere.
                asks = ringsAlarms,
                onWrite = onOpenTopThree
            )

            // A countdown running in the foreground service, surfaced where the
            // app opens. Above the goal cards because it is the only thing on
            // this screen that is happening *now*.
            if (timer.active) {
                RunningSessionCard(
                    phaseLabel = timer.phase.label,
                    remainingSeconds = timer.remainingSeconds,
                    running = timer.running,
                    onOpen = onOpenPomodoro
                )
            }
            // A session on the watch or the other phone or tablet: one tap runs it
            // here too. The plain watch card stays for what cannot be joined (a
            // paused run, an older watch build).
            val peer = com.ogenrwotdaniel.wakeproof.ui.pomodoro.rememberJoinablePeer()
            if (peer != null) {
                com.ogenrwotdaniel.wakeproof.ui.pomodoro.PeerSessionCard(peer)
            } else if (!timer.active || watchTimer?.sessionId.isNullOrBlank() ||
                watchTimer?.sessionId != com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService.runningSessionId
            ) {
                // Not for the session this device has joined: the card above shows it.
                WatchSessionCard(watchTimer)
            }
            MissingGrantsCard(missingGrants)

            // A companion device holds the whole schedule and arms none of it.
            // Without this the tablet looks exactly like a phone whose alarms
            // have quietly stopped working.
            if (!ringsAlarms) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Alarms don't ring on this device",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "The schedule below is shared across your devices, but " +
                                "only one of them wakes you. Change which in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // First and biggest: since 1.5 the streak is decided by focus time
            // alone, so this is the card the day is actually won on.
            FocusGoalCard(focusMinutes = today.focusMinutes, goalMinutes = focusGoal)
            ExamTargetNote(goalToday)
            StatusCard(
                title = "Pushups",
                value = when {
                    today.pushupTarget == 0 -> "—"
                    today.pushupDone -> "Done ✓"
                    today.pushupTarget == 1 -> "Pending"
                    else -> "${today.pushupCount}/${today.pushupTarget}"
                },
                detail = when {
                    today.pushupTarget == 0 -> "No pushup alarm due today"
                    else -> nextPushup?.let { "Alarm at $it" } ?: "No alarm set"
                },
                highlight = today.pushupDone
            )
            if (today.reviewTarget > 0 || today.reviewCount > 0) {
                StatusCard(
                    title = "Flashcard review",
                    value = when {
                        today.reviewTarget == 0 || today.reviewDone -> "Done ✓"
                        today.reviewTarget == 1 -> "Pending"
                        else -> "${today.reviewCount}/${today.reviewTarget}"
                    },
                    detail = if (today.reviewCards > 0) "${today.reviewCards} cards at alarms today"
                    else "Due cards silence the alarm",
                    highlight = today.reviewTarget == 0 || today.reviewDone
                )
            }
            // Only for people who use math alarms — a permanent "—" card would be noise for the rest.
            if (today.mathTarget > 0 || today.mathCount > 0) {
                StatusCard(
                    title = "Math",
                    value = when {
                        today.mathTarget == 0 -> "${today.mathCount} solved"
                        today.mathDone -> "Done ✓"
                        today.mathTarget == 1 -> "Pending"
                        else -> "${today.mathCount}/${today.mathTarget}"
                    },
                    detail = when {
                        today.mathTarget == 0 -> "No math alarm due today"
                        else -> nextMath?.let { "Alarm at $it" } ?: "No alarm set"
                    },
                    highlight = today.mathDone
                )
            }

            ExamSection(
                exams = exams,
                onAdd = { addingExam = true },
                onDelete = viewModel::deleteExam
            )

            Text(
                text = "Focus — last 7 days",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WeekChart(week = week, goalMinutes = focusGoal)
            MorningAlarmsWeek(week = week)

            // Six identical filled buttons stacked full-width gave every
            // destination the same weight, so the screen ended in a wall with
            // no shape to it. Starting a study session is the one thing this
            // dashboard is for; the rest is navigation and reads as a list.
            Button(onClick = onOpenPomodoro, modifier = Modifier.fillMaxWidth()) {
                Text(if (timer.active) "Back to the timer" else "Start a study session")
            }
            NavigationRow("Forest", "A tree for every focus block", onOpenForest)
            NavigationRow("Flashcards", "Review what's due today", onOpenFlashcards)
            NavigationRow("Agenda", "Today's calendar events", onOpenAgenda)
            NavigationRow("Session summaries", "The page written after each focus session", onOpenSummaries)
            NavigationRow("Screen time", "Usage and daily app limits", onOpenUsage)
            NavigationRow("Settings", "Alarms, focus, sync, data", onOpenSettings)
            NavigationRow("Setup check", "Permissions and reliability", onOpenOnboarding)
        }
    }

    if (addingExam) {
        AddExamDialog(
            onConfirm = { name, epochDay ->
                viewModel.addExam(name, epochDay)
                addingExam = false
            },
            onDismiss = { addingExam = false }
        )
    }
}

/**
 * "There is a session running" — the countdown, and a way back into it.
 *
 * Tinted rather than outlined so it reads as live state instead of as one more
 * thing to tap; the whole card is the tap target, because on a dashboard the
 * thing you want after seeing a countdown is the countdown.
 */
@Composable
private fun RunningSessionCard(
    phaseLabel: String,
    remainingSeconds: Int,
    running: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (running) phaseLabel else "$phaseLabel (paused)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Tap to open the timer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = "%d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

/**
 * A session running on the watch that cannot be joined from here (paused, or
 * from an older watch build); a joinable one gets `PeerSessionCard`. Not
 * tappable: this is a window onto the watch's timer. Ticks locally from the watch's phase
 * end, like the watch does for the phone's timer; renders nothing when idle.
 */
@Composable
private fun WatchSessionCard(timer: LiveWatchTimer?, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (timer?.active == true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    val display = LiveWatchTimerRules.display(timer, now) ?: return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${display.phaseLabel} on your watch" + if (display.running) "" else " (paused)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (display.awaitingWatch) "Waiting for the watch…" else display.blockLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = "%d:%02d".format(display.remainingSeconds / 60, display.remainingSeconds % 60),
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

/**
 * One-tap rows for the special grants a wall needs and lacks. Without them the
 * wall does nothing and nothing says so: Do Not Disturb during a session was
 * "not working" for exactly this reason. Renders nothing once all are granted.
 */
@Composable
private fun MissingGrantsCard(missing: MissingGrants, modifier: Modifier = Modifier) {
    if (!missing.dnd && !missing.notifications) return
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Some walls are switched off", style = MaterialTheme.typography.titleSmall)
            if (missing.dnd) {
                Text(
                    text = "Do Not Disturb can't follow your focus sessions without access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }) { Text("Allow Do Not Disturb") }
            }
            if (missing.notifications) {
                Text(
                    text = "Walled apps still show their notifications — in the shade, the " +
                        "Edge panel and icon previews.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                WindowNotificationGuard.component(context).flattenToString()
                            )
                        )
                    }
                }) { Text("Allow notification access") }
            }
        }
    }
}

/**
 * One destination in the list below the dashboard.
 *
 * A row with a subtitle rather than a button: it says what is behind the name,
 * which a one-word button cannot, and it stops six destinations competing with
 * the one action on the screen.
 */
@Composable
private fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Countdown chips for upcoming exams; the closest one gets error color. */
@Composable
private fun ExamSection(
    exams: List<com.ogenrwotdaniel.wakeproof.data.db.Exam>,
    onAdd: () -> Unit,
    onDelete: (com.ogenrwotdaniel.wakeproof.data.db.Exam) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Exams",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onAdd) { Text("+ Add") }
        }
        val today = LocalDate.now().toEpochDay()
        exams.forEachIndexed { index, exam ->
            val daysLeft = (exam.epochDay - today).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = exam.name, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (daysLeft) {
                            0 -> "TODAY"
                            1 -> "tomorrow"
                            else -> "in $daysLeft days"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == 0 || daysLeft <= 3) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { onDelete(exam) }) { Text("×") }
                }
            }
        }
        if (exams.isEmpty()) {
            Text(
                text = "No exams tracked — add one to start the countdown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Name + date entry for a new exam. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddExamDialog(
    onConfirm: (String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val dateState = androidx.compose.material3.rememberDatePickerState(
        // A local day 8 out, as the picker's UTC midnight: the first day heldExamsAfter
        // does not hold (it holds from the day before exam week), so an untouched default
        // is not held today; it is from tomorrow, like any planned exam. Now + days read
        // in UTC is a local day short before 09:00 in Korea.
        initialSelectedDateMillis = java.time.LocalDate.now().plusDays(com.ogenrwotdaniel.wakeproof.distraction.EXAM_WEEK_DAYS + 1L).toEpochDay() * 24L * 60 * 60 * 1000
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exam") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Exam name") },
                    singleLine = true
                )
                androidx.compose.material3.DatePicker(state = dateState, showModeToggle = false)
                // Exam week holds once begun (see heldExamsAfter): say so before it is too late.
                val pickedDay = dateState.selectedDateMillis?.let { it / (24L * 60 * 60 * 1000) }
                val today = java.time.LocalDate.now().toEpochDay()
                // The same range heldExamsAfter holds: exam week, and the day before it.
                if (pickedDay != null && pickedDay - today in 0..com.ogenrwotdaniel.wakeproof.distraction.EXAM_WEEK_DAYS) {
                    val starts = if (pickedDay - today == com.ogenrwotdaniel.wakeproof.distraction.EXAM_WEEK_DAYS.toLong()) "tomorrow" else "now"
                    // The service gives no grace on a doubted clock (heldExamsAfter): do not promise one.
                    val dialogContext = LocalContext.current
                    val clockDoubted = remember {
                        com.ogenrwotdaniel.wakeproof.distraction.SIDELOADED_BUILD && runCatching {
                            com.ogenrwotdaniel.wakeproof.distraction.clockUntrusted(dialogContext)
                        }.getOrDefault(false)
                    }
                    val grace = if (clockDoubted) {
                        "Check the date before saving: this device's clock is not trusted, so it is held " +
                            "within minutes,"
                    } else {
                        "Check the date: you have at least ten minutes to fix it. After that it is held,"
                    }
                    Text(
                        "This starts exam week $starts: gated apps stay shut all day until the exam. " +
                            "$grace and changing or deleting the exam will not end it early.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (pickedDay != null && pickedDay > today) {
                    // A later date is held too, with no warning on the day: say it here.
                    Text(
                        "Gated apps shut for the seven days up to the exam. From the day before " +
                            "those, the exam is held: changing or deleting it will not end them early.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = dateState.selectedDateMillis ?: return@TextButton
                    onConfirm(name, millis / (24L * 60 * 60 * 1000))
                },
                enabled = name.isNotBlank() && dateState.selectedDateMillis != null
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    detail: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Focus time per day against the daily goal — the same thing the streak is
 * scored on. Each bar is labelled with its own value, the goal is the dashed
 * line, and a red dot under a day marks an emergency skip of an alarm.
 */
@Composable
private fun WeekChart(week: List<DayStat>, goalMinutes: Int, modifier: Modifier = Modifier) {
    val today = LocalDate.now().toEpochDay()
    LabeledBarChart(
        bars = week.map { day ->
            ChartBar(
                label = chartDayLabel(day.epochDay, today),
                value = day.focusMinutes.toFloat(),
                valueLabel = if (day.focusMinutes > 0) formatCompactDuration(day.focusMinutes) else "",
                emphasized = day.epochDay == today,
                reached = day.outcome == DayOutcome.MET,
                flagged = day.skipped
            )
        },
        goal = goalMinutes.toFloat().takeIf { goalMinutes > 0 },
        goalLabel = "goal ${formatDurationMinutes(goalMinutes)}",
        modifier = modifier
    )
}

/**
 * Today's focus against the goal as a bar, not a fraction: "47/120 min" made you
 * do the sum, and this is the number the streak now turns on.
 */
/** Why today's goal is higher than the one set, and how to share it between the exams coming. */
@Composable
private fun ExamTargetNote(goal: com.ogenrwotdaniel.wakeproof.data.FocusGoalToday) {
    val exam = goal.exam ?: return
    if (!goal.raised && goal.split.size < 2) return
    val days = when (goal.daysLeft) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in ${goal.daysLeft} days"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (goal.raised) {
            Text(
                text = "${exam.name} is $days: goal raised from " +
                    "${formatDurationMinutes(goal.baseMinutes)} to ${formatDurationMinutes(goal.minutes)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (goal.split.size >= 2) {
            Text(
                text = "Suggested split: " + goal.split.joinToString(" · ") {
                    "${it.name} ${formatDurationMinutes(it.minutes)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FocusGoalCard(focusMinutes: Int, goalMinutes: Int, modifier: Modifier = Modifier) {
    val reached = goalMinutes > 0 && focusMinutes >= goalMinutes
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (reached) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(text = "Focus today", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = formatDurationMinutes(focusMinutes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = {
                    if (goalMinutes <= 0) 0f
                    else (focusMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text(
                text = when {
                    goalMinutes <= 0 -> "No daily focus goal set"
                    reached -> "Goal of ${formatDurationMinutes(goalMinutes)} reached — today counts 🔥"
                    else -> "${formatDurationMinutes(goalMinutes - focusMinutes)} to go for today's " +
                        "${formatDurationMinutes(goalMinutes)} goal"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The locked morning alarms over the last 7 days (2026-11-04): pushup
 * verifications against pushup alarms due, one cell per day. A schedule that
 * has quietly stopped ringing shows up here as a row of misses, rather than as
 * the one morning nobody woke. Past days are scored against today's schedule,
 * like the rest of the stats.
 */
@Composable
private fun MorningAlarmsWeek(week: List<DayStat>, modifier: Modifier = Modifier) {
    if (week.none { it.pushupTarget > 0 }) return
    val today = LocalDate.now().toEpochDay()
    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val kept = week.count { it.pushupDone }
            val due = week.count { it.pushupTarget > 0 && it.epochDay < today }
            Text(text = "Morning alarms", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Done on $kept of the last ${week.size} days" +
                    if (due > 0 && kept < due) " — check the misses below." else ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                week.forEach { day ->
                    val date = LocalDate.ofEpochDay(day.epochDay)
                    val label = if (day.epochDay == today) "Today" else
                        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
                    val mark = when {
                        day.pushupTarget == 0 -> "–"
                        day.pushupDone -> "✓"
                        day.epochDay == today -> "${day.pushupCount}/${day.pushupTarget}"
                        else -> "✗ ${day.pushupCount}/${day.pushupTarget}"
                    }
                    val color = when {
                        day.pushupDone -> MaterialTheme.colorScheme.primary
                        day.pushupTarget > 0 && day.epochDay < today -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = mark, style = MaterialTheme.typography.bodyMedium, color = color)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * What last evening put first for today, and from 19:50 the ask for
 * tomorrow's. Read against the clock once a minute, so the evening card
 * appears on a Home left open.
 */
@Composable
private fun TopThreeCards(all: com.ogenrwotdaniel.wakeproof.plan.TopThrees, asks: Boolean, onWrite: () -> Unit) {
    val now by androidx.compose.runtime.produceState(java.time.LocalDateTime.now()) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = java.time.LocalDateTime.now()
        }
    }
    val today = now.toLocalDate().toEpochDay()
    val todays = com.ogenrwotdaniel.wakeproof.plan.topThreeFor(all, today)
    if (todays.isNotEmpty()) {
        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Today's top three", style = MaterialTheme.typography.titleMedium)
                todays.forEachIndexed { index, item ->
                    Text(text = "${index + 1}. $item", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    val tomorrows = com.ogenrwotdaniel.wakeproof.plan.topThreeWrittenOn(all, today)
    val evening = com.ogenrwotdaniel.wakeproof.plan.topThreeEvening(now.hour * 60 + now.minute)
    if ((asks && evening) || tomorrows.isNotEmpty()) {
        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Tomorrow's top three", style = MaterialTheme.typography.titleMedium)
                if (tomorrows.isEmpty()) {
                    Text(
                        text = "Write the three things that matter most tomorrow before bed.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    tomorrows.forEachIndexed { index, item ->
                        Text(text = "${index + 1}. $item", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) {
                    Text(if (tomorrows.isEmpty()) "Write them" else "Change")
                }
            }
        }
    }
}
