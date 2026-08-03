package com.ogenrwotdaniel.wakeproof.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.alarm.AlarmScheduler
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityChecker
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityStatus
import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.remote.AiProvider
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.pomodoro.SpotifyController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val settingsRepository: SettingsRepository,
    private val completionRepository: CompletionRepository,
    private val reliabilityChecker: ReliabilityChecker,
    spotifyController: SpotifyController
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

    data class KeyUiState(
        val provider: AiProvider = AiProvider.GEMINI,
        val hasKey: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null
    )

    val alarms: StateFlow<List<Alarm>> = alarmRepository.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun setSoundFadeSeconds(value: Int) {
        viewModelScope.launch { settingsRepository.setSoundFadeSeconds(value.coerceIn(0, 15)) }
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

    /** True when the Spotify app is present, so the UI can adapt its wording. */
    val spotifyInstalled: Boolean = spotifyController.isInstalled()

    fun setSpotifyPlaylistUri(value: String) {
        viewModelScope.launch { settingsRepository.setSpotifyPlaylistUri(value) }
    }

    fun setDailyFocusGoalMinutes(value: Int) {
        viewModelScope.launch { settingsRepository.setDailyFocusGoalMinutes(value.coerceIn(30, 480)) }
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
}
