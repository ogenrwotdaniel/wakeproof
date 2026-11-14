package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import com.ogenrwotdaniel.wakeproof.study.RecallState
import com.ogenrwotdaniel.wakeproof.study.RecallStore
import com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore
import com.ogenrwotdaniel.wakeproof.study.SummaryState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.GeminiRepository
import com.ogenrwotdaniel.wakeproof.data.GeminiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThought
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThoughtDao
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

/** Prefix marking a `ParkedThought` entry as an Euler *Adversaria* open research conjecture/problem. */
const val ADVERSARIA_PREFIX = "[Q:"

/** Formats an open conjecture or problem for Euler's *Adversaria* ledger. */
fun formatAdversaria(subject: String, question: String): String =
    "$ADVERSARIA_PREFIX ${subject.trim().ifBlank { "General" }}] ${question.trim()}"

/** True when [thought] is an open problem/conjecture in the *Adversaria* ledger rather than a fleeting distraction. */
fun ParkedThought.isAdversaria(): Boolean = text.startsWith(ADVERSARIA_PREFIX)

/** Pomodoro screen state: the shared timer flow plus the Google Gemini study chat. */
@HiltViewModel
class PomodoroViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val settingsRepository: SettingsRepository,
    private val parkedThoughtDao: ParkedThoughtDao,
    private val recallStore: RecallStore,
    private val summaryStore: SessionSummaryStore,
    keyStore: ApiKeyStore
) : ViewModel() {

    /** Euler's Adversaria (open conjectures) and distracting thoughts captured mid-focus, oldest done last. */
    val thoughts: StateFlow<List<ParkedThought>> = parkedThoughtDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Optional open problem from the Adversaria ledger pinned as the focus target for the current session. */
    private val _activeConjecture = MutableStateFlow<String?>(null)
    val activeConjecture: StateFlow<String?> = _activeConjecture

    fun selectActiveConjecture(text: String?) {
        _activeConjecture.value = text
    }

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

    fun logConjecture(subject: String, question: String) {
        if (question.isBlank()) return
        parkThought(formatAdversaria(subject, question))
    }

    fun setThoughtDone(thought: ParkedThought, done: Boolean) {
        viewModelScope.launch {
            parkedThoughtDao.updateContent(thought.id, thought.text, done)
            if (done && _activeConjecture.value == thought.text) {
                _activeConjecture.value = null
            }
        }
    }

    fun clearDoneThoughts() {
        viewModelScope.launch { parkedThoughtDao.clearDone() }
    }

    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state
    val hasGeminiKey: Boolean = keyStore.hasKey()

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
            settingsRepository.setFocusSoundId(id)
        }
    }

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
        val socratic: Boolean = false,
        val error: String? = null
    )

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat

    fun setSocraticMode(enabled: Boolean) {
        _chat.update { it.copy(socratic = enabled) }
    }

    fun sendChat(text: String) {
        if (text.isBlank() || _chat.value.sending) return
        val history = _chat.value.messages + ChatMessage("user", text.trim())
        val useSocratic = _chat.value.socratic
        _chat.update { it.copy(messages = history, sending = true, error = null) }
        viewModelScope.launch {
            when (val result = geminiRepository.studyChat(history.map { it.role to it.text }, socratic = useSocratic)) {
                is GeminiResult.Success -> _chat.update {
                    it.copy(
                        messages = it.messages + ChatMessage("assistant", result.value),
                        sending = false
                    )
                }
                is GeminiResult.ApiError -> _chat.update {
                    it.copy(sending = false, error = "API error: ${result.message}")
                }
                GeminiResult.NetworkError -> _chat.update {
                    it.copy(sending = false, error = "Network error.")
                }
                GeminiResult.NoKey -> _chat.update {
                    it.copy(sending = false, error = "No API key configured.")
                }
            }
        }
    }
}
