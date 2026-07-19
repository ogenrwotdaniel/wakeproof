package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One study-chat message: role ("user"/"assistant") + text. */
data class ChatMessage(val role: String, val text: String)

/** Pomodoro screen state: the shared timer flow plus the AI study chat. */
@HiltViewModel
class PomodoroViewModel @Inject constructor(
    private val kimiRepository: KimiRepository,
    private val settingsRepository: SettingsRepository,
    keyStore: ApiKeyStore
) : ViewModel() {

    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state
    val hasKimiKey: Boolean = keyStore.hasKey()

    val settings: StateFlow<WakeProofSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WakeProofSettings())

    fun setMusicEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusMusicEnabled(enabled) }
    }

    data class ChatState(
        val messages: List<ChatMessage> = emptyList(),
        val sending: Boolean = false,
        val error: String? = null
    )

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat

    fun sendChat(text: String) {
        if (text.isBlank() || _chat.value.sending) return
        val history = _chat.value.messages + ChatMessage("user", text.trim())
        _chat.update { it.copy(messages = history, sending = true, error = null) }
        viewModelScope.launch {
            when (val result = kimiRepository.studyChat(history.map { it.role to it.text })) {
                is KimiResult.Success -> _chat.update {
                    it.copy(
                        messages = it.messages + ChatMessage("assistant", result.value),
                        sending = false
                    )
                }
                is KimiResult.ApiError -> _chat.update {
                    it.copy(sending = false, error = "API error: ${result.message}")
                }
                KimiResult.NetworkError -> _chat.update {
                    it.copy(sending = false, error = "Network error.")
                }
                KimiResult.NoKey -> _chat.update {
                    it.copy(sending = false, error = "No API key configured.")
                }
            }
        }
    }
}
