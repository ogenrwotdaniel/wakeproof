package com.ogenrwotdaniel.wakeproof.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.BuildConfig
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityStatus
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType

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
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    var editingAlarm by remember { mutableStateOf<Alarm?>(null) }
    var deletingAlarm by remember { mutableStateOf<Alarm?>(null) }
    var addingAlarm by remember { mutableStateOf(false) }

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = alarm.enabled,
                            onCheckedChange = { viewModel.setAlarmEnabled(alarm, it) }
                        )
                        TextButton(onClick = { deletingAlarm = alarm }) {
                            Text(
                                text = "Delete",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { addingAlarm = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add alarm") }

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
                label = "Bottle size",
                value = settings.bottleCapacityMl,
                range = 250f..2000f,
                unit = "ml",
                onCommit = viewModel::setBottleCapacityMl
            )
            Text(
                text = "Your bottle's full capacity — how the camera check turns the drop in " +
                    "the water level into millilitres. No printed ml marks needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SliderSetting(
                label = "Emergency hold",
                value = settings.emergencyHoldSeconds,
                range = 30f..120f,
                unit = "s",
                onCommit = viewModel::setEmergencyHoldSeconds
            )

            HorizontalDivider()

            Text(text = "Focus sessions", style = MaterialTheme.typography.titleMedium)
            SliderSetting(
                label = "Daily focus goal",
                value = settings.dailyFocusGoalMinutes,
                range = 30f..480f,
                unit = "min",
                onCommit = viewModel::setDailyFocusGoalMinutes
            )
            SwitchSetting(
                label = "Study sound",
                description = "Sound plays automatically during focus phases. Pick the source " +
                    "on the Pomodoro screen — an ambient loop (rain, fireplace…) or Spotify, " +
                    "one or the other. Turn off for silence.",
                checked = settings.focusMusicEnabled,
                onCheckedChange = viewModel::setFocusMusicEnabled
            )
            if (settings.focusMusicEnabled || settings.breakMusicEnabled) {
                SliderSetting(
                    label = "Sound fade in / out",
                    value = settings.soundFadeSeconds,
                    range = 0f..15f,
                    unit = "s",
                    onCommit = viewModel::setSoundFadeSeconds
                )
                Text(
                    text = "The ambient loop eases up from silence when a phase starts and " +
                        "back down before it ends, instead of snapping on and off. " +
                        "0 turns fading off. Spotify is controlled with media keys, which " +
                        "carry no volume, so it is unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SwitchSetting(
                label = "Keep screen awake in sessions",
                description = "The screen never times out while a timer is counting down — " +
                    "focus AND breaks, on any screen of the app, and over other apps too " +
                    "while the floating timer bubble is showing. Only pausing or stopping " +
                    "lets it sleep again.",
                checked = settings.sessionKeepScreenOn,
                onCheckedChange = viewModel::setSessionKeepScreenOn
            )
            SwitchSetting(
                label = "Lock phone during focus",
                description = "Pins the phone to WakeProof while a focus timer runs; " +
                    "unlocks on breaks.",
                checked = settings.focusLockPhone,
                onCheckedChange = viewModel::setFocusLockPhone
            )
            SwitchSetting(
                label = "Floating timer",
                description = "Shows a draggable countdown bubble over other apps " +
                    "while a Pomodoro timer runs.",
                checked = settings.focusFloatingTimer,
                onCheckedChange = viewModel::setFocusFloatingTimer
            )
            if (settings.focusFloatingTimer) {
                val context = LocalContext.current
                var overlayAllowed by remember {
                    mutableStateOf(android.provider.Settings.canDrawOverlays(context))
                }
                LifecycleResumeEffect(Unit) {
                    overlayAllowed = android.provider.Settings.canDrawOverlays(context)
                    onPauseOrDispose { }
                }
                if (!overlayAllowed) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${context.packageName}".toUri()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow display over other apps") }
                }
            }

            HorizontalDivider()

            SpotifySection(
                playlistUri = settings.spotifyPlaylistUri,
                installed = viewModel.spotifyInstalled,
                onPlaylistChange = viewModel::setSpotifyPlaylistUri
            )

            HorizontalDivider()

            ApiKeySection(
                provider = keyState.provider,
                hasKey = keyState.hasKey,
                testing = keyState.testing,
                testResult = keyState.testResult,
                onProviderChange = viewModel::setProvider,
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

            SyncSection()

            HorizontalDivider()

            Text(text = "Data", style = MaterialTheme.typography.titleMedium)
            val csvLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/csv")
            ) { uri -> uri?.let(viewModel::exportCsv) }
            OutlinedButton(
                onClick = { csvLauncher.launch("wakeproof-history.csv") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export history as CSV") }
            exportMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.startsWith("Exported")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            Text(text = "About", style = MaterialTheme.typography.titleMedium)
            Text(
                // Read from BuildConfig so the About line cannot drift from the
                // version actually shipped.
                text = "WakeProof ${BuildConfig.VERSION_NAME} — alarms you can only " +
                    "dismiss by proving it. " +
                    "Pushups are verified on-device; water via the selected vision AI " +
                    "(Kimi or Gemini).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }

    if (addingAlarm) {
        AddAlarmDialog(
            onConfirm = { type, hour, minute, label ->
                viewModel.addAlarm(type, hour, minute, label)
                addingAlarm = false
            },
            onDismiss = { addingAlarm = false }
        )
    }

    // Confirmed rather than immediate: with sync on, deleting here also removes the
    // alarm from the user's other devices, and there is no undo.
    deletingAlarm?.let { alarm ->
        AlertDialog(
            onDismissRequest = { deletingAlarm = null },
            title = { Text("Delete this alarm?") },
            text = {
                val time = "%02d:%02d".format(alarm.hour, alarm.minute)
                val name = alarm.label.ifBlank { alarm.type.name.lowercase() }
                Text(
                    "$time ($name) will be removed from this device, and from any " +
                        "other device signed into the same account."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlarm(alarm)
                    deletingAlarm = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingAlarm = null }) { Text("Cancel") }
            }
        )
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

/**
 * Spotify hand-off settings: the playlist the study button opens (paste the
 * "Share → Copy link" URL). Whether playback follows the timer is not a switch
 * here — it follows from picking Spotify as the study sound on the Pomodoro
 * screen. No Spotify login happens either: WakeProof only deep-links out and
 * sends media keys, so there is nothing to authorise.
 */
@Composable
private fun SpotifySection(
    playlistUri: String,
    installed: Boolean,
    onPlaylistChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Seeded once per saved value: typing must not be clobbered by DataStore emissions.
    var draft by remember(playlistUri) { mutableStateOf(playlistUri) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Spotify", style = MaterialTheme.typography.titleMedium)
        if (!installed) {
            Text(
                text = "Spotify isn't installed — the button on the Pomodoro screen will " +
                    "offer to install it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Focus playlist link") },
            placeholder = { Text("https://open.spotify.com/playlist/…") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onPlaylistChange(draft) },
                enabled = draft.trim() != playlistUri
            ) { Text("Save") }
            if (playlistUri.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        draft = ""
                        onPlaylistChange("")
                    }
                ) { Text("Clear") }
            }
        }
        Text(
            text = "In Spotify: playlist → ⋯ → Share → Copy link, then paste it here. " +
                "Left blank, the button just opens Spotify.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "To let Spotify be your study sound, pick it in the sound row on the " +
                "Pomodoro screen. It then plays and pauses with the timer, and no ambient " +
                "loop plays underneath it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun SwitchSetting(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
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

/**
 * Creates an extra alarm — a second pushup session, another water reminder.
 *
 * A new alarm never conflicts across devices: it gets its own sync id, so one
 * added on the phone and one added on the tablet are two separate alarms rather
 * than two versions of the same one.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddAlarmDialog(
    onConfirm: (AlarmType, Int, Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    var type by rememberSaveable { mutableStateOf(AlarmType.PUSHUP) }
    var label by rememberSaveable { mutableStateOf("") }
    val state = rememberTimePickerState(initialHour = 6, initialMinute = 0, is24Hour = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add alarm") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // CUSTOM is left out on purpose: it has no verification screen,
                    // so offering it here would create an alarm nothing can dismiss.
                    listOf(AlarmType.PUSHUP, AlarmType.WATER).forEach { candidate ->
                        FilterChip(
                            selected = candidate == type,
                            onClick = { type = candidate },
                            label = { Text(candidate.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true
                )
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(type, state.hour, state.minute, label) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
