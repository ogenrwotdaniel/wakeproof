package com.ogenrwotdaniel.wakeproof.ui.home

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.BuildConfig
import com.ogenrwotdaniel.wakeproof.data.DayOutcome
import com.ogenrwotdaniel.wakeproof.data.DayStat
import com.ogenrwotdaniel.wakeproof.ui.components.ChartBar
import com.ogenrwotdaniel.wakeproof.ui.components.LabeledBarChart
import com.ogenrwotdaniel.wakeproof.ui.components.chartDayLabel
import com.ogenrwotdaniel.wakeproof.ui.formatCompactDuration
import com.ogenrwotdaniel.wakeproof.ui.formatDurationMinutes
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
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
    onOpenCorpusCapture: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val nextPushup by viewModel.nextPushupAlarm.collectAsStateWithLifecycle()
    val nextMath by viewModel.nextMathAlarm.collectAsStateWithLifecycle()
    val focusGoal by viewModel.focusGoalMinutes.collectAsStateWithLifecycle()
    val exams by viewModel.exams.collectAsStateWithLifecycle()
    val ringsAlarms by viewModel.ringsAlarms.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    var testArmed by remember { mutableStateOf(false) }
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
            StatusCard(
                title = "Water",
                value = if (today.waterTargetCount == 0) "—" else
                    "${today.waterCount}/${today.waterTargetCount}" +
                        if (today.anyUnverifiedWater) " *" else "",
                detail = if (today.waterTargetCount == 0) "No water alarm due today"
                else "${today.waterMl} ml today",
                highlight = today.waterDone
            )
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
            NavigationRow("Screen time", "Usage and daily app limits", onOpenUsage)
            NavigationRow("Settings", "Alarms, focus, sync, data", onOpenSettings)
            NavigationRow("Setup check", "Permissions and reliability", onOpenOnboarding)

            if (BuildConfig.DEBUG) {
                Text(
                    text = "Developer testing",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onOpenCorpusCapture,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Water corpus capture") }
                OutlinedButton(
                    onClick = {
                        viewModel.testFire(AlarmType.PUSHUP)
                        testArmed = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test-fire pushup alarm (10 s)") }
                OutlinedButton(
                    onClick = {
                        viewModel.testFire(AlarmType.WATER)
                        testArmed = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test-fire water alarm (10 s)") }
                // Borrows the pushup alarm and swaps its mission, so trying the
                // math flow creates no MATH row for older builds to choke on.
                OutlinedButton(
                    onClick = {
                        viewModel.testFire(AlarmType.PUSHUP, mission = AlarmType.MATH)
                        testArmed = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test-fire math alarm (10 s)") }
                if (testArmed) {
                    Text(
                        text = "Armed — lock your phone now to test the lock-screen wake.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
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
        initialSelectedDateMillis = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
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
