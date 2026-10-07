package com.ogenrwotdaniel.wakeproof.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.alarm.AlarmDays
import com.ogenrwotdaniel.wakeproof.alarm.AlarmDevicePolicy
import com.ogenrwotdaniel.wakeproof.alarm.AlarmInitializer
import com.ogenrwotdaniel.wakeproof.alarm.AlarmScheduler
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityChecker
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityStatus
import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.remote.AiProvider
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import dagger.hilt.android.lifecycle.HiltViewModel
import com.ogenrwotdaniel.wakeproof.data.backup.BackupRepository
import com.ogenrwotdaniel.wakeproof.data.backup.backupFilename
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings state: alarm times/enabled (rescheduled immediately on change),
 * goal targets, and Kimi API key management.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: ApiKeyStore,
    private val kimiRepository: KimiRepository,
    private val alarmRepository: AlarmRepository,
    private val alarmScheduler: AlarmScheduler,
    private val alarmDevicePolicy: AlarmDevicePolicy,
    private val alarmInitializer: AlarmInitializer,
    private val settingsRepository: SettingsRepository,
    private val completionRepository: CompletionRepository,
    private val reliabilityChecker: ReliabilityChecker,
    private val backupRepository: BackupRepository,
    private val usageStatsRepository: UsageStatsRepository
) : ViewModel() {

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage

    /** Writes every completion log as CSV to the user-picked [uri] (SAF). */
    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val logs = completionRepository.getAll()
                val csv = buildString {
                    appendLine("date,kind,status,amount,subject,alarmId")
                    logs.forEach { log ->
                        appendLine(
                            listOf(
                                LocalDate.ofEpochDay(log.epochDay).toString(),
                                log.kind.name,
                                log.status.name,
                                log.amount.toString(),
                                "\"${(log.subject ?: "").replace("\"", "\"\"")}\"",
                                log.alarmId?.toString() ?: ""
                            ).joinToString(",")
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(csv.toByteArray())
                    } ?: error("Could not open the selected file")
                }
                logs.size
            }
            _exportMessage.value = result.fold(
                onSuccess = { "Exported $it entries." },
                onFailure = { "Export failed: ${it.message}" }
            )
        }
    }

    /**
     * Writes a whole-app backup to [uri].
     *
     * Separate from the CSV export, which is a *report* — human-readable
     * history for reading elsewhere, deliberately lossy, and no use at all for
     * putting the app back. This one is the restorable copy.
     */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val document = backupRepository.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(document.toByteArray())
                    } ?: error("Could not open the selected file")
                }
                document
            }
            _exportMessage.value = result.fold(
                onSuccess = { "Exported backup (${it.length / 1024} KB)." },
                onFailure = { "Backup failed: ${it.message}" }
            )
        }
    }

    /**
     * Restores a backup from [uri], merging it over what is already here.
     *
     * Failure is reported rather than thrown: the likeliest causes are a file
     * that is not a backup at all and one written by a much newer build, and
     * neither should take the app down while the user is trying to recover it.
     */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val document = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("Could not open the selected file")
                }
                backupRepository.import(document)
            }
            _exportMessage.value = result.fold(
                onSuccess = { "Restored ${it.totalRows} rows. Alarms rescheduled." },
                onFailure = { "Restore failed: ${it.message}" }
            )
        }
    }

    /** Today's date, for the suggested backup filename. */
    fun suggestedBackupName(): String = backupFilename(LocalDate.now().toString())

    data class KeyUiState(
        val provider: AiProvider = AiProvider.GEMINI,
        val hasKey: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null
    )

    val alarms: StateFlow<List<Alarm>> = alarmRepository.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether this device is the one that rings. See [AlarmDevicePolicy]. */
    val ringsAlarms: StateFlow<Boolean> = alarmDevicePolicy.ringsAlarmsFlow

    /**
     * Switching the role re-runs the initializer, which is what turns the
     * setting into actual armed or cancelled intents — flipping the flag alone
     * would leave whatever AlarmManager already holds exactly where it was.
     */
    fun setRingsAlarms(value: Boolean) {
        alarmDevicePolicy.setRingsAlarms(value)
        viewModelScope.launch { alarmInitializer.initialize() }
    }

    val settings: StateFlow<WakeProofSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WakeProofSettings())

    private val _keyState = MutableStateFlow(
        KeyUiState(provider = keyStore.getProvider(), hasKey = keyStore.hasKey())
    )
    val keyState: StateFlow<KeyUiState> = _keyState

    private val _reliability = MutableStateFlow(reliabilityChecker.check())
    val reliability: StateFlow<ReliabilityStatus> = _reliability

    fun refreshReliability() {
        _reliability.value = reliabilityChecker.check()
    }

    /** Changing a time reschedules the alarm immediately. */
    fun setAlarmTime(alarm: Alarm, hour: Int, minute: Int) {
        viewModelScope.launch {
            val updated = alarm.copy(hour = hour, minute = minute)
            alarmRepository.updateAlarm(updated)
            if (updated.enabled) alarmScheduler.schedule(updated) else alarmScheduler.cancel(updated)
        }
    }

    fun setAlarmEnabled(alarm: Alarm, enabled: Boolean) {
        viewModelScope.launch {
            val updated = alarm.copy(enabled = enabled)
            alarmRepository.updateAlarm(updated)
            if (enabled) alarmScheduler.schedule(updated) else alarmScheduler.cancel(updated)
        }
    }

    /** Changing which days an alarm repeats on reschedules it for the next one. */
    fun setAlarmDays(alarm: Alarm, daysMask: Int) {
        viewModelScope.launch {
            val updated = alarm.copy(daysMask = AlarmDays.normalise(daysMask))
            alarmRepository.updateAlarm(updated)
            if (updated.enabled) alarmScheduler.schedule(updated) else alarmScheduler.cancel(updated)
        }
    }

    /** Adds a pushup or water alarm at the chosen time, on the chosen days. */
    fun addAlarm(type: AlarmType, hour: Int, minute: Int, label: String, daysMask: Int) {
        viewModelScope.launch { alarmRepository.addAlarm(type, hour, minute, label, daysMask) }
    }

    /**
     * Deletes an alarm. Needed once alarms sync: a device can now receive alarms
     * it never created, and before this there was no way to get rid of one.
     */
    fun deleteAlarm(alarm: Alarm) {
        viewModelScope.launch { alarmRepository.deleteAlarm(alarm) }
    }

    fun setOfflineWaterVerification(value: Boolean) {
        viewModelScope.launch { settingsRepository.setOfflineWaterVerification(value) }
    }

    fun setPushupTarget(value: Int) {
        viewModelScope.launch { settingsRepository.setPushupTarget(value.coerceIn(5, 50)) }
    }

    fun setWaterAmountMl(value: Int) {
        viewModelScope.launch { settingsRepository.setWaterAmountMl(value.coerceIn(250, 1000)) }
    }

    fun setBottleCapacityMl(value: Int) {
        viewModelScope.launch { settingsRepository.setBottleCapacityMl(value.coerceIn(250, 2000)) }
    }

    fun setEmergencyHoldSeconds(value: Int) {
        viewModelScope.launch { settingsRepository.setEmergencyHoldSeconds(value.coerceIn(30, 120)) }
    }

    fun setFocusMusicEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusMusicEnabled(value) }
    }

    /**
     * Clamp MUST match the slider's range in SettingsScreen (0..[MAX_SOUND_FADE_SECONDS]).
     * It was left at 15 when the range went to 60 on 2026-08-30, so the slider read 60,
     * the label read 60, and the stored value was silently 15 — a setting that looked
     * applied and was not.
     */
    fun setSoundFadeSeconds(value: Int) {
        viewModelScope.launch {
            settingsRepository.setSoundFadeSeconds(value.coerceIn(0, MAX_SOUND_FADE_SECONDS))
        }
    }

    fun setFocusImmersiveTimer(value: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusImmersiveTimer(value) }
    }

    fun setFocusLockPhone(value: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusLockPhone(value) }
    }

    fun setFocusFloatingTimer(value: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusFloatingTimer(value) }
    }

    fun setSessionKeepScreenOn(value: Boolean) {
        viewModelScope.launch { settingsRepository.setSessionKeepScreenOn(value) }
    }

    fun setFocusQuotes(value: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusQuotes(value) }
    }

    fun setDeepFocus(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDeepFocus(value) }
    }

    /** Every launchable app, for the study-app picker. Loaded on first use. */
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch { _installedApps.value = usageStatsRepository.installedApps() }
    }

    fun appLabel(packageName: String): String = usageStatsRepository.appLabel(packageName)

    fun toggleStudyApp(packageName: String) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().studyApps
            settingsRepository.setStudyApps(
                if (packageName in current) current - packageName else current + packageName
            )
        }
    }

    /**
     * Clamp MUST match the slider's range in SettingsScreen
     * ([MIN_DAILY_FOCUS_GOAL_MINUTES]..[MAX_DAILY_FOCUS_GOAL_MINUTES]) — the
     * same bound written down twice is what silently capped the sound fade at
     * 15 s while the slider offered 60 (see [setSoundFadeSeconds]), so both ends
     * read the constants rather than their own literals.
     */
    fun setDailyFocusGoalMinutes(value: Int) {
        viewModelScope.launch {
            settingsRepository.setDailyFocusGoalMinutes(
                value.coerceIn(MIN_DAILY_FOCUS_GOAL_MINUTES, MAX_DAILY_FOCUS_GOAL_MINUTES)
            )
        }
    }

    /** Switching provider re-reads that provider's own saved key. */
    fun setProvider(provider: AiProvider) {
        keyStore.setProvider(provider)
        _keyState.update {
            it.copy(provider = provider, hasKey = keyStore.hasKey(), testResult = null)
        }
    }

    fun saveKey(key: String) {
        if (key.isBlank()) return
        keyStore.setKey(key)
        _keyState.update { it.copy(hasKey = true, testResult = null) }
    }

    fun clearKey() {
        keyStore.clear()
        _keyState.update { it.copy(hasKey = false, testResult = null) }
    }

    fun testConnection() {
        _keyState.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val message = when (val result = kimiRepository.testConnection()) {
                is KimiResult.Success -> "Connected — the key works."
                is KimiResult.ApiError ->
                    "API error${result.code?.let { " $it" } ?: ""}: ${result.message}"
                KimiResult.NetworkError -> "Network error — check your connection."
                KimiResult.NoKey -> "Save a key first."
            }
            _keyState.update { it.copy(testing = false, testResult = message) }
        }
    }

    companion object {
        /**
         * Upper bound of the sound fade, shared with the Settings slider so the
         * two cannot drift apart again (see [setSoundFadeSeconds]).
         */
        const val MAX_SOUND_FADE_SECONDS = 60

        /**
         * Bounds of the daily focus goal, shared with the Settings slider.
         *
         * Twelve hours, not the old eight: eight is a working day, and the
         * people who set a focus goal at all are the ones revising for exams
         * who blew past it and had nothing left to aim at. The floor stays at
         * half an hour — a goal below one pomodoro is met before it is set.
         */
        const val MIN_DAILY_FOCUS_GOAL_MINUTES = 30
        const val MAX_DAILY_FOCUS_GOAL_MINUTES = 720

        /**
         * Slider stops for the goal, one per quarter hour across the range.
         * A free slider over 690 minutes lands on numbers like 437.
         */
        const val DAILY_FOCUS_GOAL_STEPS =
            (MAX_DAILY_FOCUS_GOAL_MINUTES - MIN_DAILY_FOCUS_GOAL_MINUTES) / 15 - 1
    }
}
