package com.ogenrwotdaniel.wakeproof.ui.home

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.BuildConfig
import com.ogenrwotdaniel.wakeproof.data.DayStat
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Dashboard: streak, today's goal cards, 7-day history chart (pure Canvas),
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
    onOpenCorpusCapture: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val nextPushup by viewModel.nextPushupAlarm.collectAsStateWithLifecycle()
    val focusGoal by viewModel.focusGoalMinutes.collectAsStateWithLifecycle()
    val exams by viewModel.exams.collectAsStateWithLifecycle()
    val ringsAlarms by viewModel.ringsAlarms.collectAsStateWithLifecycle()
    var testArmed by remember { mutableStateOf(false) }
    var addingExam by remember { mutableStateOf(false) }

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
                text = "🔥 ${streak.days}-day streak" +
                    (if (streak.asterisk) " *" else "") +
                    (if (streak.asterisk) "  (* includes honor-mode water)" else ""),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
            StatusCard(
                title = "Focus",
                value = "${today.focusMinutes}/$focusGoal min",
                detail = if (today.focusMinutes >= focusGoal) "Daily goal reached 🎉"
                else "Daily focus goal",
                highlight = today.focusMinutes >= focusGoal
            )

            ExamSection(
                exams = exams,
                onAdd = { addingExam = true },
                onDelete = viewModel::deleteExam
            )

            Text(
                text = "Last 7 days",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WeekChart(week = week)

            Button(onClick = onOpenPomodoro, modifier = Modifier.fillMaxWidth()) {
                Text("Pomodoro study mode")
            }
            Button(onClick = onOpenFlashcards, modifier = Modifier.fillMaxWidth()) {
                Text("Flashcards")
            }
            Button(onClick = onOpenAgenda, modifier = Modifier.fillMaxWidth()) {
                Text("Agenda — calendar events")
            }
            Button(onClick = onOpenUsage, modifier = Modifier.fillMaxWidth()) {
                Text("Phone usage")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
            OutlinedButton(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Onboarding")
            }

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
 * Bars = the share of that day's goals that were met, where the goals are the
 * alarms due that day (see [com.ogenrwotdaniel.wakeproof.data.dailyGoalsFrom]).
 * A day with no alarms due draws empty rather than full. Red base marks a day
 * with an emergency skip; the label is the weekday letter.
 */
@Composable
private fun WeekChart(week: List<DayStat>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val error = MaterialTheme.colorScheme.error
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

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
                val fraction = day.goalProgress
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
                if (day.skipped) {
                    drawRect(
                        color = error,
                        topLeft = Offset(left, size.height - 6f),
                        size = Size(barWidth, 6f)
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
