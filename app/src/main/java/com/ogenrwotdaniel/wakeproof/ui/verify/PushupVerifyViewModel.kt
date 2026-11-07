package com.ogenrwotdaniel.wakeproof.ui.verify

import com.ogenrwotdaniel.wakeproof.alarm.StrictAlarm
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.verify.Achievement
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
        val resets: Int = 0,
        /** The camera cannot be used; a locked alarm offers sums instead. */
        val cameraUnavailable: Boolean = false
    )

    private val counter = PushupCounter()
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val startedAt = SystemClock.elapsedRealtime()
    private var lastPoseAt = startedAt
    private var lastRealPoseAt = startedAt
    private var resetAppliedForThisGap = false

    /** Ringing the [StrictAlarm]: exactly its reps, and no emergency dismiss. */
    @Volatile private var strict = false

    fun setStrict(value: Boolean) {
        if (!value || strict) return
        strict = true
        _uiState.update {
            it.copy(target = StrictAlarm.REPS, emergencyAvailable = false)
        }
    }

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _uiState.update {
                it.copy(
                    target = if (strict) StrictAlarm.REPS
                    else settings.pushupTarget,
                    emergencyHoldSeconds = settings.emergencyHoldSeconds
                )
            }
        }
        viewModelScope.launch {
            while (!_uiState.value.verified) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                // A camera held by something else binds without complaint and
                // simply stops delivering, which would leave a locked alarm no way out.
                if (cameraStalled(now, watchingCameraSince, lastFrameAt)) onCameraUnavailable()
                // Black frames still count as frames, and a locked alarm has no
                // timed exit of its own. Long enough to be no shortcut to the sums.
                if (strict && watchingCameraSince != null && strictCameraBlind(now, lastRealPoseAt) &&
                    !_uiState.value.cameraUnavailable
                ) {
                    onCameraUnavailable()
                }
                // Only with a camera that could have seen one: a fault must not
                // cost the reps already earned, or count as a reset.
                if (watchingCameraSince != null && now - lastPoseAt > NO_POSE_RESET_MS &&
                    !resetAppliedForThisGap
                ) {
                    resetAppliedForThisGap = true
                    counter.reset()
                    _uiState.update { it.copy(reps = 0, resets = it.resets + 1) }
                }
                refreshEmergency()
            }
        }
    }

    @Volatile private var lastFrameAt = 0L
    @Volatile private var watchingCameraSince: Long? = null
    private val permission = CameraPermissionLatch()
    private var pausedAt: Long? = null

    fun onPoseFrame(frame: PoseFrame) {
        if (_uiState.value.verified) return
        lastFrameAt = SystemClock.elapsedRealtime()
        if (frame.pose != null) {
            lastPoseAt = SystemClock.elapsedRealtime()
            lastRealPoseAt = lastPoseAt
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

    /** The one place the hatch is decided, so a screen's promise cannot be taken back. */
    private fun refreshEmergency() {
        val state = _uiState.value
        val open = !strict && (
            state.cameraUnavailable || state.resets >= MAX_RESETS ||
                SystemClock.elapsedRealtime() - startedAt > EMERGENCY_AFTER_MS
            )
        if (open != state.emergencyAvailable) _uiState.update { it.copy(emergencyAvailable = open) }
    }

    /** No permission, or no lens or frames ⇒ verification is impossible; open the escape hatch. */
    fun onCameraUnavailable() {
        watchingCameraSince = null
        _uiState.update { it.copy(cameraUnavailable = true) }
        refreshEmergency()
    }

    /** The user asked to try again, or the permission came back, so the hatch closes. */
    fun onCameraAvailable() {
        // A fresh start: the time spent without a camera is already written off.
        pausedAt = null
        lastPoseAt = SystemClock.elapsedRealtime()
        lastRealPoseAt = lastPoseAt
        resetAppliedForThisGap = false
        _uiState.update { it.copy(cameraUnavailable = false) }
        refreshEmergency()
    }

    /**
     * Only a change back to granted reopens the camera. This also runs on every
     * rotation, which must not take back a fallback a dead camera has earned.
     */
    fun onCameraPermission(granted: Boolean) =
        permission.report(granted, onLost = ::onCameraUnavailable, onBack = ::onCameraAvailable)

    /**
     * The preview is on screen and frames should be coming, from a fresh bind on.
     * Off while stopped, when CameraX closes the camera on purpose.
     */
    fun watchCamera(watching: Boolean) {
        val now = SystemClock.elapsedRealtime()
        watchingCameraSince = if (watching) now else null
        if (!watching) {
            pausedAt = now
        } else {
            // Time away is not time the camera failed to see anyone: neither
            // wait counts it, so returning costs no reps and earns no sums. A
            // flip pauses for a moment only, so it cannot hold the wait off.
            pausedAt?.let { val away = now - it; lastPoseAt += away; lastRealPoseAt += away }
            pausedAt = null
        }
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
                motivationSource.motivationalMessage(Achievement.Pushups(_uiState.value.target))
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

/** How long a locked alarm's camera may go without seeing anyone at all. */
const val STRICT_NO_POSE_MS = 10 * 60_000L

/** Pure: see [STRICT_NO_POSE_MS]. */
fun strictCameraBlind(now: Long, lastRealPoseAt: Long): Boolean =
    now - lastRealPoseAt > STRICT_NO_POSE_MS

/** How long a watched camera may go without a frame. */
const val NO_FRAME_MS = 15_000L

/**
 * Pure: no frame for [NO_FRAME_MS] of a camera being watched, however good the
 * ones before it were. Null [watchingSince] = not watched.
 */
fun cameraStalled(now: Long, watchingSince: Long?, lastFrameAt: Long): Boolean =
    watchingSince != null && now - maxOf(watchingSince, lastFrameAt) > NO_FRAME_MS
