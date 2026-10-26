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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ogenrwotdaniel.wakeproof.ui.deviceUses24HourClock
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import com.ogenrwotdaniel.wakeproof.ui.formatDurationMinutes
import androidx.compose.ui.unit.dp
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.BuildConfig
import com.ogenrwotdaniel.wakeproof.alarm.AlarmDays
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityStatus
import android.app.role.RoleManager
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.ui.components.AppPickerDialog
import com.ogenrwotdaniel.wakeproof.alarm.WakeCheck
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.verify.math.MathChallenge
import java.time.DayOfWeek

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
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    val ringsAlarms by viewModel.ringsAlarms.collectAsStateWithLifecycle()
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
            SwitchSetting(
                label = "Ring alarms on this device",
                description = "Alarms are shared across your devices, but only the ones with " +
                    "this switch on actually ring. Leave it off on a tablet so a single " +
                    "05:00 alarm does not go off twice in the same room. Off here means " +
                    "nothing is armed at all — the alarms still list, and editing them still " +
                    "reaches your phone.",
                checked = ringsAlarms,
                onCheckedChange = viewModel::setRingsAlarms
            )
            if (!ringsAlarms) {
                Text(
                    text = "This device is a companion: it shows the schedule and the stats, " +
                        "and rings nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            alarms.forEach { alarm ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        TextButton(onClick = { editingAlarm = alarm }) {
                            Text(
                                text = formatAlarmTime(LocalContext.current, alarm.hour, alarm.minute),
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        Text(
                            text = alarm.label.ifBlank { alarm.type.name.lowercase() } +
                                " · " + AlarmDays.label(alarm.daysMask),
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
                DayOfWeekChips(
                    daysMask = alarm.daysMask,
                    onToggle = { day ->
                        viewModel.setAlarmDays(alarm, AlarmDays.toggle(alarm.daysMask, day))
                    }
                )
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
                label = "Math problems in a row",
                value = settings.mathProblems,
                range = MathChallenge.MIN_PROBLEMS.toFloat()..MathChallenge.MAX_PROBLEMS.toFloat(),
                unit = "sums",
                onCommit = viewModel::setMathProblems,
                steps = MathChallenge.MAX_PROBLEMS - MathChallenge.MIN_PROBLEMS - 1
            )
            SwitchSetting(
                label = "Wake-up check",
                description = "${WakeCheck.DELAY_MINUTES} minutes after you dismiss an alarm, " +
                    "a notification asks if you're still up. Ignore it for " +
                    "${WakeCheck.ANSWER_MINUTES} minutes and the alarm rings again.",
                checked = settings.wakeUpCheck,
                onCheckedChange = viewModel::setWakeUpCheck
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
            SwitchSetting(
                label = "Read the water level on this device",
                description = "Finds the water line with on-device computer vision: no API " +
                    "key, no network, no waiting at 06:00, and the photos never leave the " +
                    "phone. A vision model is still asked for a second opinion before any " +
                    "rejection is shown, if you have a key. Turn this off to send every " +
                    "pair to the model instead — worth trying if your own bottle is " +
                    "misread. Verification needs a translucent bottle either way; a steel " +
                    "flask has no visible line at any price.",
                checked = settings.offlineWaterVerification,
                onCheckedChange = viewModel::setOfflineWaterVerification
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
                range = SettingsViewModel.MIN_DAILY_FOCUS_GOAL_MINUTES.toFloat()..
                    SettingsViewModel.MAX_DAILY_FOCUS_GOAL_MINUTES.toFloat(),
                unit = "min",
                steps = SettingsViewModel.DAILY_FOCUS_GOAL_STEPS,
                valueText = ::formatDurationMinutes,
                onCommit = viewModel::setDailyFocusGoalMinutes
            )
            Text(
                text = "How much focused time a day counts as a full day — the goal on " +
                    "Home, the widget and the watch. Up to 12 hours, in quarter-hour steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    range = 0f..SettingsViewModel.MAX_SOUND_FADE_SECONDS.toFloat(),
                    unit = "s",
                    onCommit = viewModel::setSoundFadeSeconds
                )
                Text(
                    text = "The ambient loop eases up from silence when a phase starts and " +
                        "back down before it ends, instead of snapping on and off. " +
                        "0 turns fading off. The fade-out starts this many seconds " +
                        "before the phase ends, so a long fade wants a long phase — " +
                        "60 s on a 5 min break spends a fifth of it easing away. " +
                        "Spotify is controlled with media keys, which carry no " +
                        "volume, so it is unaffected.",
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
                label = "Full-screen timer while focusing",
                description = "During a focus block the app shows the countdown and " +
                    "nothing else — no chat, no sound picker, no other screens — " +
                    "until the break. Pause and end stay available.",
                checked = settings.focusImmersiveTimer,
                onCheckedChange = viewModel::setFocusImmersiveTimer
            )
            SwitchSetting(
                label = "Lock phone during the session",
                description = "Pins the phone to WakeProof for the whole session, " +
                    "breaks included. Pausing or ending the session unpins it.",
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

            SwitchSetting(
                label = "Quotes while focusing",
                description = "A short line under the countdown, a different one each block.",
                checked = settings.focusQuotes,
                onCheckedChange = viewModel::setFocusQuotes
            )
            SwitchSetting(
                label = "Grow a tree each focus block",
                description = "Forest-style: a tree grows while you focus. Leave WakeProof " +
                    "for anything that isn't a study app for more than 10 seconds and it " +
                    "withers — that block won't count toward your daily goal. Needs usage " +
                    "access. An alternative to locking the phone that still lets you " +
                    "use your study apps.",
                checked = settings.deepFocus,
                onCheckedChange = viewModel::setDeepFocus
            )
            StudyAppsSection(
                studyApps = settings.studyApps,
                focusCreditApp = settings.focusCreditApp,
                installedApps = installedApps,
                labelOf = viewModel::appLabel,
                onOpenPicker = viewModel::loadInstalledApps,
                onToggle = viewModel::toggleStudyApp,
                onFocusCreditApp = viewModel::setFocusCreditApp
            )

            HorizontalDivider()

            SpotifySection(
                playlistUri = settings.spotifyPlaylistUri,
                installed = viewModel.spotifyInstalled,
                onPlaylistChange = viewModel::setSpotifyPlaylistUri
            )

            HorizontalDivider()

            LauncherSection()

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

            // The CSV above is a report. This is the restorable copy — and the
            // only one there is: allowBackup is false, the app is not installed
            // from Play, and cloud sync replicates deletions rather than
            // guarding against them.
            val backupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri -> uri?.let(viewModel::exportBackup) }
            OutlinedButton(
                onClick = { backupLauncher.launch(viewModel.suggestedBackupName()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back up everything to a file") }

            val restoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let(viewModel::importBackup) }
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore from a backup file") }
            Text(
                text = "A restore merges the file over what is on this device: rows in " +
                    "both take the file's version, and anything only on this device is " +
                    "kept. Alarms are rescheduled afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            onConfirm = { type, hour, minute, label, daysMask ->
                viewModel.addAlarm(type, hour, minute, label, daysMask)
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
                val time = formatAlarmTime(LocalContext.current, alarm.hour, alarm.minute)
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

/**
 * Opt-in to WakeProof's minimalist home screen. Uses the system's own
 * default-home request rather than a switch of ours, so it is always the phone,
 * not WakeProof, that decides what Home opens — and going back to One UI Home
 * is the same system screen every other launcher uses.
 */
@Composable
private fun LauncherSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    var isHome by remember { mutableStateOf(roleManager.isRoleHeld(RoleManager.ROLE_HOME)) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { isHome = roleManager.isRoleHeld(RoleManager.ROLE_HOME) }
    LifecycleResumeEffect(Unit) {
        isHome = roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        onPauseOrDispose { }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Minimalist home screen", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Replace the home screen with a quiet, text-only one: the time, your focus " +
                "today, up to ${com.ogenrwotdaniel.wakeproof.launcher.LauncherViewModel.MAX_FAVORITES} " +
                "pinned apps and a searchable list of the rest. No icons, no badges. Apps " +
                "you've set a limit on are dimmed and open only after a 5-second pause.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isHome) {
            Text(
                text = "✓ WakeProof is your home screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Switch back to another home screen") }
        } else {
            Button(
                onClick = {
                    if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                        request.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    } else {
                        context.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Use WakeProof as home screen") }
        }
    }
}

/**
 * Apps that count as studying. They are the focus list: during a session they
 * are the only apps that open (everything else meets the focus wall), and on
 * the Screen time page their minutes are shown as study time rather than
 * counted against you. Adding is locked while a session runs.
 */
@Composable
private fun StudyAppsSection(
    studyApps: Set<String>,
    focusCreditApp: String,
    installedApps: List<InstalledApp>,
    labelOf: (String) -> String,
    onOpenPicker: () -> Unit,
    onToggle: (String) -> Unit,
    onFocusCreditApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var picking by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val sessionRunning by PomodoroTimerService.state
            .collectAsStateWithLifecycle()
        Text(text = "Study apps", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Your focus list: the only apps that open during a focus session " +
                "(the phone always works). Also shown as study time on the Screen time page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        studyApps.sortedBy { labelOf(it).lowercase() }.forEach { pkg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = labelOf(pkg), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { onToggle(pkg) }) { Text("Remove") }
            }
        }
        OutlinedButton(
            onClick = {
                onOpenPicker()
                picking = true
            },
            enabled = !sessionRunning.active,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (sessionRunning.active) "Locked until the session ends" else "Add a study app…")
        }

        Text(text = "Focus time is spent in", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Focus-timer time that no screen measured — a block timed on your " +
                "watch, or studied on paper — is shown as time in this app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val choices = (studyApps + focusCreditApp).filter { it.isNotBlank() }
                .sortedBy { labelOf(it).lowercase() }
            choices.forEach { pkg ->
                FilterChip(
                    selected = pkg == focusCreditApp,
                    onClick = { onFocusCreditApp(pkg) },
                    label = { Text(labelOf(pkg)) }
                )
            }
            FilterChip(
                selected = focusCreditApp.isBlank(),
                onClick = { onFocusCreditApp("") },
                label = { Text("Don't count it") }
            )
        }
    }
    if (picking) {
        AppPickerDialog(
            title = "Study apps",
            apps = installedApps,
            marked = studyApps,
            markLabel = "✓",
            onPick = { onToggle(it.packageName) },
            onDismiss = { picking = false }
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
    modifier: Modifier = Modifier,
    /** Discrete stops between the ends; 0 (the default) leaves the slider free. */
    steps: Int = 0,
    /** Reading for the current value; the default is "<n> <unit>". */
    valueText: ((Int) -> String)? = null
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$label: " + (valueText?.invoke(sliderValue.toInt())
                ?: "${sliderValue.toInt()} $unit"),
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCommit(sliderValue.toInt()) },
            valueRange = range,
            steps = steps
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
    onConfirm: (AlarmType, Int, Int, String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var type by rememberSaveable { mutableStateOf(AlarmType.PUSHUP) }
    var label by rememberSaveable { mutableStateOf("") }
    var daysMask by rememberSaveable { mutableIntStateOf(AlarmDays.EVERY_DAY) }
    val state = rememberTimePickerState(
        initialHour = 6,
        initialMinute = 0,
        is24Hour = deviceUses24HourClock(LocalContext.current)
    )

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
                    listOf(AlarmType.PUSHUP, AlarmType.WATER, AlarmType.MATH).forEach { candidate ->
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
                Text(text = "Repeats", style = MaterialTheme.typography.bodyMedium)
                DayOfWeekChips(
                    daysMask = daysMask,
                    onToggle = { day -> daysMask = AlarmDays.toggle(daysMask, day) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(type, state.hour, state.minute, label, daysMask) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The seven repeat chips.
 *
 * Deselecting the last remaining day is refused rather than allowed and then
 * quietly reinterpreted — an alarm set to no days is either a mistake or a
 * silent alarm, and neither is worth offering. See [AlarmDays.toggle].
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DayOfWeekChips(
    daysMask: Int,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AlarmDays.ORDER.forEach { day ->
            FilterChip(
                selected = AlarmDays.contains(daysMask, day),
                onClick = { onToggle(day) },
                label = { Text(AlarmDays.shortName(day)) }
            )
        }
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
        is24Hour = deviceUses24HourClock(LocalContext.current)
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
