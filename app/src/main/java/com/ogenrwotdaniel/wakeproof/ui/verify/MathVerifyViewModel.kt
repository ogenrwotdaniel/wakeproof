package com.ogenrwotdaniel.wakeproof.ui.verify

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.verify.math.MathChallenge
import com.ogenrwotdaniel.wakeproof.verify.math.MathProgress
import com.ogenrwotdaniel.wakeproof.verify.math.submitAnswer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the math mission: a run of [MathChallenge] problems answered on an
 * in-app keypad. A wrong answer restarts the run with a new problem.
 *
 * The emergency dismiss opens after 5 minutes or [MAX_MISTAKES] wrong answers —
 * the math equivalent of the pushup screen's three resets. Settings are read
 * with a fallback because this can run before the first unlock, when DataStore
 * is unreadable; an alarm must still be dismissable there.
 */
@HiltViewModel
class MathVerifyViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val completionRepository: CompletionRepository
) : ViewModel() {

    data class UiState(
        val progress: MathProgress,
        val input: String = "",
        /** Bumped on every wrong answer, so the screen can shake once per mistake. */
        val wrongPulse: Int = 0,
        val verified: Boolean = false,
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60
    )

    private val random = Random(SystemClock.elapsedRealtimeNanos())
    private val startedAt = SystemClock.elapsedRealtime()

    private val _uiState = MutableStateFlow(
        UiState(
            progress = MathProgress(
                problem = MathChallenge.next(random),
                solved = 0,
                required = MathChallenge.DEFAULT_PROBLEMS,
                mistakes = 0
            )
        )
    )
    val uiState: StateFlow<UiState> = _uiState

    init {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
                ?: return@launch
            _uiState.update {
                it.copy(
                    progress = it.progress.copy(required = settings.mathProblems),
                    emergencyHoldSeconds = settings.emergencyHoldSeconds
                )
            }
        }
        viewModelScope.launch {
            while (!_uiState.value.verified) {
                delay(1_000)
                refreshEmergency()
            }
        }
    }

    fun onDigit(digit: Char) {
        _uiState.update {
            if (it.verified || it.input.length >= MathChallenge.MAX_ANSWER_DIGITS) it
            else it.copy(input = (it.input + digit).trimStart('0').ifEmpty { "0" })
        }
    }

    fun onBackspace() {
        _uiState.update { it.copy(input = it.input.dropLast(1)) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.verified || state.input.isEmpty()) return
        val next = submitAnswer(state.progress, state.input.toIntOrNull(), random)
        val wrong = next.mistakes > state.progress.mistakes
        _uiState.update {
            it.copy(
                progress = next,
                input = "",
                wrongPulse = if (wrong) it.wrongPulse + 1 else it.wrongPulse
            )
        }
        refreshEmergency()
        if (next.done) onVerifiedInternal()
    }

    fun logSkipped(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { completionRepository.log(GoalKind.MATH, CompletionStatus.SKIPPED) }
            onDone()
        }
    }

    private fun refreshEmergency() {
        val state = _uiState.value
        val open = state.progress.mistakes >= MAX_MISTAKES ||
            SystemClock.elapsedRealtime() - startedAt > EMERGENCY_AFTER_MS
        if (open != state.emergencyAvailable) _uiState.update { it.copy(emergencyAvailable = open) }
    }

    private fun onVerifiedInternal() {
        if (_uiState.value.verified) return
        _uiState.update { it.copy(verified = true) }
        viewModelScope.launch {
            runCatching {
                completionRepository.log(
                    GoalKind.MATH,
                    CompletionStatus.VERIFIED,
                    amount = _uiState.value.progress.required
                )
            }
        }
    }

    private companion object {
        const val EMERGENCY_AFTER_MS = 5 * 60_000L

        /** Enough wrong answers that the user is plainly awake and plainly stuck. */
        const val MAX_MISTAKES = 10
    }
}
