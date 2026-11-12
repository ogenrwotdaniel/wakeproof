package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import com.ogenrwotdaniel.wakeproof.study.RecallState
import com.ogenrwotdaniel.wakeproof.study.RecallStore
import com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore
import com.ogenrwotdaniel.wakeproof.study.SummaryState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThought
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThoughtDao
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.SpotifyController
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
    private val parkedThoughtDao: ParkedThoughtDao,
    private val spotify: SpotifyController,
    private val recallStore: RecallStore,
    private val summaryStore: SessionSummaryStore,
    keyStore: ApiKeyStore
) : ViewModel() {

    /** Distracting thoughts captured mid-focus, oldest done last. */
    val thoughts: StateFlow<List<ParkedThought>> = parkedThoughtDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The recall notes and the block still waiting for one; the screen decides if it is still due. */
    val recall: StateFlow<RecallState> = recallStore.state

    /** The session summary still owed its photo; the screen decides if it is still due. */
    val summary: StateFlow<SummaryState> = summaryStore.state

    init {
        viewModelScope.launch { recallStore.load() }
        viewModelScope.launch { summaryStore.load() }
    }

    fun saveRecall(text: String) {
        viewModelScope.launch { recallStore.answer(text) }
    }

    fun skipRecall() {
        viewModelScope.launch { recallStore.skip() }
    }

    fun parkThought(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            parkedThoughtDao.insert(
                ParkedThought(text = text.trim(), createdAtMillis = System.currentTimeMillis())
            )
        }
    }

    fun setThoughtDone(thought: ParkedThought, done: Boolean) {
        viewModelScope.launch {
            parkedThoughtDao.updateContent(thought.id, thought.text, done)
        }
    }

    fun clearDoneThoughts() {
        viewModelScope.launch { parkedThoughtDao.clearDone() }
    }

    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state
    val hasKimiKey: Boolean = keyStore.hasKey()

    val settings: StateFlow<WakeProofSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WakeProofSettings())

    fun setMusicEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusMusicEnabled(enabled) }
    }

    fun setBreakMusicEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBreakMusicEnabled(enabled) }
    }

    fun setFocusSound(id: String) {
        viewModelScope.launch {
            // Choosing Spotify puts it on the focus list: not while a summary is
            // owed, when the list may only shrink.
            if (com.ogenrwotdaniel.wakeproof.pomodoro.FocusSound.isSpotify(id) &&
                !com.ogenrwotdaniel.wakeproof.pomodoro.FocusSound.isSpotify(settings.value.focusSoundId) &&
                summaryStore.owed(summaryStore.load()) != null
            ) {
                return@launch
            }
            settingsRepository.setFocusSoundId(id)
        }
    }

    /** Whether to offer Spotify controls at all (false → the button installs it). */
    val spotifyInstalled: Boolean = spotify.isInstalled()

    private val _spotifyError = MutableStateFlow<String?>(null)
    val spotifyError: StateFlow<String?> = _spotifyError

    /** Opens the saved playlist, or Spotify itself when none is configured. */
    fun openSpotify() {
        val link = settings.value.spotifyPlaylistUri
        _spotifyError.value =
            if (spotify.open(link)) null else "Couldn't open Spotify on this phone."
    }

    fun spotifyPlayPause() = spotify.playPause()
    fun spotifyNext() = spotify.next()
    fun spotifyPrevious() = spotify.previous()

    fun setActiveSubject(name: String) {
        viewModelScope.launch { settingsRepository.setActiveSubject(name) }
    }

    fun addSubject(name: String) {
        viewModelScope.launch { settingsRepository.addStudySubject(name) }
    }

    fun removeSubject(name: String) {
        viewModelScope.launch { settingsRepository.removeStudySubject(name) }
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
