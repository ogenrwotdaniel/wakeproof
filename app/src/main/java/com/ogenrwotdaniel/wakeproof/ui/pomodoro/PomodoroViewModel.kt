package com.ogenrwotdaniel.wakeproof.ui.pomodoro

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
import com.ogenrwotdaniel.wakeproof.music.MusicGroup
import com.ogenrwotdaniel.wakeproof.music.MusicLibrary
import com.ogenrwotdaniel.wakeproof.music.MusicSelection
import com.ogenrwotdaniel.wakeproof.music.Track
import com.ogenrwotdaniel.wakeproof.music.groupsOf
import com.ogenrwotdaniel.wakeproof.music.select
import com.ogenrwotdaniel.wakeproof.pomodoro.NowPlaying
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val musicLibrary: MusicLibrary,
    keyStore: ApiKeyStore
) : ViewModel() {

    /** Distracting thoughts captured mid-focus, oldest done last. */
    val thoughts: StateFlow<List<ParkedThought>> = parkedThoughtDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        viewModelScope.launch { settingsRepository.setFocusSoundId(id) }
    }

    /** What the user's library holds, grouped for the picker. Loaded on demand. */
    data class LibraryState(
        val hasPermission: Boolean = false,
        val loaded: Boolean = false,
        val tracks: List<Track> = emptyList(),
        val folders: List<MusicGroup> = emptyList(),
        val albums: List<MusicGroup> = emptyList(),
        val artists: List<MusicGroup> = emptyList(),
        /** "Music/Study" and its song count, when the selection is a picked folder. */
        val pickedFolderLabel: String? = null,
        val pickedFolderCount: Int? = null
    ) {
        fun countFor(selection: MusicSelection): Int = tracks.select(selection).size
    }

    private val _library = MutableStateFlow(LibraryState(hasPermission = musicLibrary.hasPermission()))
    val library: StateFlow<LibraryState> = _library

    /** Re-reads the library — after the permission is granted, or the picker opens. */
    fun loadLibrary() {
        viewModelScope.launch {
            val granted = musicLibrary.hasPermission()
            val tracks = musicLibrary.tracks()
            val (folders, albums, artists) = groupsOf(tracks)
            val picked = MusicSelection.decode(settingsRepository.settings.first().musicSelection)
                as? MusicSelection.PickedFolder
            _library.value = LibraryState(
                hasPermission = granted,
                loaded = true,
                tracks = tracks,
                folders = folders,
                albums = albums,
                artists = artists,
                pickedFolderLabel = picked?.let { musicLibrary.folderLabel(it.treeUri) },
                pickedFolderCount = picked?.let { musicLibrary.folderTracks(it.treeUri).size }
            )
        }
    }

    /** A folder came back from the system folder browser: keep access to it and play it. */
    fun onFolderPicked(treeUri: android.net.Uri) {
        viewModelScope.launch {
            val previous = MusicSelection.decode(settingsRepository.settings.first().musicSelection)
            musicLibrary.rememberFolder(treeUri, previous)
            settingsRepository.setMusicSelection(MusicSelection.PickedFolder(treeUri.toString()).encode())
            loadLibrary()
        }
    }

    val nowPlaying: StateFlow<NowPlaying?> = PomodoroTimerService.nowPlaying

    fun setMusicSelection(selection: MusicSelection) {
        viewModelScope.launch { settingsRepository.setMusicSelection(selection.encode()) }
    }

    fun setMusicShuffle(value: Boolean) {
        viewModelScope.launch { settingsRepository.setMusicShuffle(value) }
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
