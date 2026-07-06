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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
 * and navigation. A day counts toward the streak only when pushups and all
 * three waters are verified.
 */
@Composable
fun HomeScreen(
    onOpenOnboarding: () -> Unit,
    onOpenPomodoro: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val nextPushup by viewModel.nextPushupAlarm.collectAsStateWithLifecycle()
    var testArmed by remember { mutableStateOf(false) }

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

            StatusCard(
                title = "Pushups",
                value = if (today.pushupDone) "Done ✓" else "Pending",
                detail = nextPushup?.let { "Alarm at $it" } ?: "No alarm set",
                highlight = today.pushupDone
            )
            StatusCard(
                title = "Water",
                value = "${today.waterCount}/${today.waterTargetCount}" +
                    if (today.anyUnverifiedWater) " *" else "",
                detail = "${today.waterMl} ml today",
                highlight = today.waterCount >= today.waterTargetCount
            )
            StatusCard(
                title = "Focus",
                value = "${today.focusMinutes} min",
                detail = "Pomodoro sessions today",
                highlight = today.focusMinutes > 0
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
 * Bars = goals completed that day (pushup + up to 3 waters, max 4). Red base
 * marks a day with an emergency skip; the label is the weekday letter.
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
                val goals = (if (day.pushupDone) 1 else 0) + day.waterCount.coerceAtMost(3)
                val fraction = goals / 4f
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
