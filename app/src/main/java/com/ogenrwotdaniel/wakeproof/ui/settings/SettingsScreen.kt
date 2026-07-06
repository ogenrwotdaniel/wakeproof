package com.ogenrwotdaniel.wakeproof.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityStatus
import com.ogenrwotdaniel.wakeproof.data.db.Alarm

/**
 * Settings: alarm times + enable switches (rescheduled immediately), goal
 * targets, Kimi API key management, emergency-hold duration, about.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val keyState by viewModel.keyState.collectAsStateWithLifecycle()
    val reliability by viewModel.reliability.collectAsStateWithLifecycle()
    var editingAlarm by remember { mutableStateOf<Alarm?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshReliability()
        onPauseOrDispose { }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

            Text(text = "Alarms", style = MaterialTheme.typography.titleMedium)
            alarms.forEach { alarm ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        TextButton(onClick = { editingAlarm = alarm }) {
                            Text(
                                text = "%02d:%02d".format(alarm.hour, alarm.minute),
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        Text(
                            text = alarm.label.ifBlank { alarm.type.name.lowercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                    Switch(
                        checked = alarm.enabled,
                        onCheckedChange = { viewModel.setAlarmEnabled(alarm, it) }
                    )
                }
            }

            HorizontalDivider()

            Text(text = "Goals", style = MaterialTheme.typography.titleMedium)
            SliderSetting(
                label = "Pushup target",
                value = settings.pushupTarget,
                range = 5f..50f,
                unit = "reps",
                onCommit = viewModel::setPushupTarget
            )
            SliderSetting(
                label = "Water per alarm",
                value = settings.waterAmountMl,
                range = 250f..1000f,
                unit = "ml",
                onCommit = viewModel::setWaterAmountMl
            )
            SliderSetting(
                label = "Emergency hold",
                value = settings.emergencyHoldSeconds,
                range = 30f..120f,
                unit = "s",
                onCommit = viewModel::setEmergencyHoldSeconds
            )

            HorizontalDivider()

            ApiKeySection(
                hasKey = keyState.hasKey,
                testing = keyState.testing,
                testResult = keyState.testResult,
                onSave = viewModel::saveKey,
                onClear = viewModel::clearKey,
                onTest = viewModel::testConnection
            )

            HorizontalDivider()

            ReliabilitySection(
                status = reliability,
                onRecheck = viewModel::refreshReliability
            )

            HorizontalDivider()

            Text(text = "About", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "WakeProof 0.1.0 — alarms you can only dismiss by proving it. " +
                    "Pushups are verified on-device; water via the Kimi vision API.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }

    editingAlarm?.let { alarm ->
        TimePickerDialog(
            initialHour = alarm.hour,
            initialMinute = alarm.minute,
            onConfirm = { hour, minute ->
                viewModel.setAlarmTime(alarm, hour, minute)
                editingAlarm = null
            },
            onDismiss = { editingAlarm = null }
        )
    }
}

@Composable
private fun ReliabilitySection(
    status: ReliabilityStatus,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Reliability check", style = MaterialTheme.typography.titleMedium)
        CheckRow("Notifications", status.notifications)
        CheckRow("Camera", status.camera)
        CheckRow("Full-screen alarms", status.fullScreenIntent)
        CheckRow("Exact alarms", status.exactAlarm)
        CheckRow("Battery unrestricted", status.batteryUnrestricted)
        if (!status.allOk) {
            Text(
                text = "Something regressed — the 5 AM alarm may not fire. Re-grant the " +
                    "items above (Onboarding on the home screen walks you through it).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        TextButton(onClick = onRecheck) { Text("Re-check now") }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = (if (ok) "✅ " else "❌ ") + label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$label: ${sliderValue.toInt()} $unit",
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCommit(sliderValue.toInt()) },
            valueRange = range
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = { TimePicker(state = state) }
    )
}
