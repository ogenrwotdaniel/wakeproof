package com.ogenrwotdaniel.wakeproof.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.plan.MAX_TOP_THREE_ITEM_LENGTH
import com.ogenrwotdaniel.wakeproof.plan.TopThree
import com.ogenrwotdaniel.wakeproof.plan.TopThreeStore
import com.ogenrwotdaniel.wakeproof.plan.cleanTopThree
import com.ogenrwotdaniel.wakeproof.plan.topThreeEveningDay
import com.ogenrwotdaniel.wakeproof.plan.topThreeWrittenOn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Writing tomorrow's top three. Nothing is judged: one line is enough to save,
 * three are asked for. Saving again the same evening replaces the list.
 */
@HiltViewModel
class TopThreeViewModel @Inject constructor(
    private val store: TopThreeStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val completionRepository: com.ogenrwotdaniel.wakeproof.data.CompletionRepository,
    settingsRepository: com.ogenrwotdaniel.wakeproof.data.SettingsRepository
) : ViewModel() {

    data class UiState(
        val items: List<String> = listOf("", "", ""),
        /** The stored list has been looked up; until then the fields stay locked. */
        val loaded: Boolean = false,
        val saved: Boolean = false,
        /** The 19:50 alarm's emergency dismiss, open after [EMERGENCY_AFTER_MS] of ringing. */
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60
    ) {
        val canSave: Boolean get() = loaded && items.any { it.isNotBlank() }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // Read at each use, not once: the screen can be left open into another day,
    // and a list saved then belongs to that evening ([topThreeEveningDay]).
    private fun evening(): Long = java.time.LocalDateTime.now().let {
        topThreeEveningDay(it.toLocalDate().toEpochDay(), it.hour * 60 + it.minute)
    }
    private var saving = false

    init {
        viewModelScope.launch {
            // Written already this evening: shown again to be changed, not lost.
            val earlier = topThreeWrittenOn(store.load(), evening())
            _uiState.update { it.copy(items = (earlier + listOf("", "", "")).take(3), loaded = true) }
        }
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return@launch
            _uiState.update { it.copy(emergencyHoldSeconds = settings.emergencyHoldSeconds) }
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(EMERGENCY_AFTER_MS)
            _uiState.update { it.copy(emergencyAvailable = true) }
        }
    }

    /**
     * The alarm's emergency dismiss. Not logged: a SKIPPED row of any kind marks
     * the whole day's alarms missed, and three unwritten lines must not undo the
     * morning's pushups.
     */
    fun logSkipped(onDone: () -> Unit) = onDone()

    private companion object {
        const val EMERGENCY_AFTER_MS = 5 * 60_000L
    }

    fun setItem(index: Int, text: String) = _uiState.update { state ->
        if (index !in state.items.indices) state
        else state.copy(
            items = state.items.toMutableList().also { it[index] = text.take(MAX_TOP_THREE_ITEM_LENGTH) }
        )
    }

    /** [ringing]: saved to silence the 19:50 alarm, so it is logged as that alarm done. */
    fun save(ringing: Boolean = false) {
        val state = _uiState.value
        if (saving || state.saved || !state.canSave) return
        saving = true
        viewModelScope.launch {
            try {
                val items = cleanTopThree(state.items)
                store.save(TopThree(evening(), items, System.currentTimeMillis()))
                if (ringing) runCatching {
                    completionRepository.log(
                        com.ogenrwotdaniel.wakeproof.data.db.GoalKind.SHUTDOWN,
                        com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus.VERIFIED,
                        amount = items.size
                    )
                }
                // To the tablet now, not at the next hourly pass: it is read there in the morning.
                runCatching { com.ogenrwotdaniel.wakeproof.data.sync.SyncWorker.requestOneOff(context) }
                _uiState.update { it.copy(saved = true) }
            } finally {
                // A save that failed must be able to be tried again.
                saving = false
            }
        }
    }
}
