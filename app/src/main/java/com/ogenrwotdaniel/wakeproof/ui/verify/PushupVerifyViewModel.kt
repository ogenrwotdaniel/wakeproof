package com.ogenrwotdaniel.wakeproof.ui.verify

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.verify.MotivationSource
import com.ogenrwotdaniel.wakeproof.verify.pushup.PoseFrame
import com.ogenrwotdaniel.wakeproof.verify.pushup.PosePoint
import com.ogenrwotdaniel.wakeproof.verify.pushup.PushupCounter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives pushup verification: feeds camera pose frames into [PushupCounter],
 * tracks the emergency-dismiss gates (3 counter resets or 5 minutes elapsed,
 * or no camera), and logs the outcome.
 */
@HiltViewModel
class PushupVerifyViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val completionRepository: CompletionRepository,
    private val motivationSource: MotivationSource
) : ViewModel() {

    data class UiState(
        val reps: Int = 0,
        val target: Int = 15,
        val status: PushupCounter.Status = PushupCounter.Status.NO_POSE,
        val skeleton: List<PosePoint> = emptyList(),
        val imageWidth: Int = 1,
        val imageHeight: Int = 1,
        val verified: Boolean = false,
        val motivation: String? = null,
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60,
        val resets: Int = 0
    )

    private val counter = PushupCounter()
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val startedAt = SystemClock.elapsedRealtime()
    private var lastPoseAt = startedAt
    private var resetAppliedForThisGap = false

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _uiState.update {
                it.copy(
                    target = settings.pushupTarget,
                    emergencyHoldSeconds = settings.emergencyHoldSeconds
                )
            }
        }
        viewModelScope.launch {
            while (!_uiState.value.verified) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                if (now - lastPoseAt > NO_POSE_RESET_MS && !resetAppliedForThisGap) {
                    resetAppliedForThisGap = true
                    counter.reset()
                    _uiState.update { it.copy(reps = 0, resets = it.resets + 1) }
                }
                val emergency = _uiState.value.resets >= MAX_RESETS ||
                    now - startedAt > EMERGENCY_AFTER_MS
                if (emergency != _uiState.value.emergencyAvailable) {
                    _uiState.update { it.copy(emergencyAvailable = emergency) }
                }
            }
        }
    }

    fun onPoseFrame(frame: PoseFrame) {
        if (_uiState.value.verified) return
        if (frame.pose != null) {
            lastPoseAt = SystemClock.elapsedRealtime()
            resetAppliedForThisGap = false
        }
        val result = counter.process(frame.pose)
        _uiState.update {
            it.copy(
                reps = result.reps,
                status = result.status,
                skeleton = frame.skeletonPoints,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight
            )
        }
        if (result.reps >= _uiState.value.target) {
            onVerifiedInternal()
        }
    }

    /** Camera permission denied ⇒ verification is impossible; open the escape hatch. */
    fun onCameraUnavailable() {
        _uiState.update { it.copy(emergencyAvailable = true) }
    }

    fun logSkipped(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                completionRepository.log(GoalKind.PUSHUP, CompletionStatus.SKIPPED)
            }
            onDone()
        }
    }

    private fun onVerifiedInternal() {
        if (_uiState.value.verified) return
        _uiState.update { it.copy(verified = true) }
        viewModelScope.launch {
            runCatching {
                completionRepository.log(
                    GoalKind.PUSHUP, CompletionStatus.VERIFIED, amount = _uiState.value.target
                )
            }
            val message = runCatching {
                motivationSource.motivationalMessage("Completed ${_uiState.value.target} pushups at 5 AM")
            }.getOrNull()
            _uiState.update { it.copy(motivation = message) }
        }
    }

    private companion object {
        const val NO_POSE_RESET_MS = 30_000L
        const val EMERGENCY_AFTER_MS = 5 * 60_000L
        const val MAX_RESETS = 3
    }
}
