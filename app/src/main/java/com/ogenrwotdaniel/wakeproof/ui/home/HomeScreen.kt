package com.ogenrwotdaniel.wakeproof.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import java.time.LocalDate

/**
 * Dashboard: today's alarms and their completion state. The stats phase turns
 * this into the full streak dashboard; the test-fire buttons are dev-only helpers
 * for exercising the real alarm pipeline and go away in the hardening phase.
 */
@Composable
fun HomeScreen(
    onOpenOnboarding: () -> Unit,
    onOpenPomodoro: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
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
                text = "Prove it. Then the alarm stops.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            alarms.forEach { alarm -> AlarmCard(alarm) }

            Button(onClick = onOpenPomodoro, modifier = Modifier.fillMaxWidth()) {
                Text("Pomodoro study mode")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
            OutlinedButton(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Onboarding")
            }

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
            ) {
                Text("Test-fire pushup alarm (10 s)")
            }
            OutlinedButton(
                onClick = {
                    viewModel.testFire(AlarmType.WATER)
                    testArmed = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test-fire water alarm (10 s)")
            }
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

@Composable
private fun AlarmCard(alarm: Alarm, modifier: Modifier = Modifier) {
    val doneToday = alarm.lastCompletedEpochDay == LocalDate.now().toEpochDay()
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "%02d:%02d".format(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = alarm.label.ifBlank { alarm.type.name.lowercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = when {
                    !alarm.enabled -> "off"
                    doneToday -> "done ✓"
                    else -> "pending"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (doneToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
