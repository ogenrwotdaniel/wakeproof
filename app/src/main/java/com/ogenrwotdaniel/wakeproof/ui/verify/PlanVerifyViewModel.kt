package com.ogenrwotdaniel.wakeproof.ui.verify

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.plan.DayPlanStore
import com.ogenrwotdaniel.wakeproof.plan.DayReviewStore
import com.ogenrwotdaniel.wakeproof.plan.topThreeFor
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the day-plan mission: photograph the page the day was written on,
 * look at it once, keep it. The photo goes to [DayPlanStore] and that is all
 * the proof asked for (the user's call, 2026-11-12: "just photo enough"); what
 * is on the page is not judged.
 *
 * The emergency dismiss opens when no photo can be taken or kept (a dead
 * camera, a revoked permission, storage still locked before the first unlock)
 * or after 5 minutes, and logs SKIPPED.
 */
@HiltViewModel
class PlanVerifyViewModel @Inject constructor(
    private val store: DayPlanStore,
    private val completionRepository: CompletionRepository,
    private val reviews: DayReviewStore,
    settingsRepository: SettingsRepository
) : ViewModel() {

    enum class Step { CAPTURE, CONFIRM, SAVING, SUCCESS }

    data class UiState(
        val step: Step = Step.CAPTURE,
        val photo: ByteArray? = null,
        /** The camera failed to start or to take a photo; offered a retry. */
        val cameraFailed: Boolean = false,
        /** The last photo could not be written to storage. */
        val saveFailed: Boolean = false,
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60,
        /** What last evening's review put first for today; empty when none was written. */
        val topThree: List<String> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val permission = CameraPermissionLatch()
    private var timedOut = false

    init {
        viewModelScope.launch {
            // Unreadable before the first unlock; the default hold stands then.
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return@launch
            _uiState.update { it.copy(emergencyHoldSeconds = settings.emergencyHoldSeconds) }
        }
        viewModelScope.launch {
            val top = topThreeFor(reviews.load(), LocalDate.now().toEpochDay())
            _uiState.update { it.copy(topThree = top) }
        }
        viewModelScope.launch {
            delay(EMERGENCY_AFTER_MS)
            timedOut = true
            _uiState.update { it.withEmergency() }
        }
    }

    /** The one place the hatch is decided. */
    private fun UiState.withEmergency() =
        copy(emergencyAvailable = cameraFailed || saveFailed || timedOut)

    fun onCameraUnavailable() {
        _uiState.update { it.copy(cameraFailed = true).withEmergency() }
    }

    /** The user asked to try again; the hatch closes unless something else opened it. */
    fun retryCamera() = onCameraWorking()

    fun onCameraPermission(granted: Boolean) =
        permission.report(granted, onLost = ::onCameraUnavailable, onBack = ::onCameraWorking)

    private fun onCameraWorking() {
        if (!_uiState.value.cameraFailed) return
        _uiState.update { it.copy(cameraFailed = false).withEmergency() }
    }

    fun onCaptured(bytes: ByteArray) {
        if (_uiState.value.step != Step.CAPTURE) return
        onCameraWorking()
        _uiState.update { it.copy(step = Step.CONFIRM, photo = bytes) }
    }

    fun onRetake() {
        if (_uiState.value.step != Step.CONFIRM) return
        _uiState.update { it.copy(step = Step.CAPTURE, photo = null) }
    }

    /** Keeps the photo; the mission is done once it is on disk. */
    fun onKeep() {
        val state = _uiState.value
        val photo = state.photo
        // The step check is what makes a double tap save one photo, not two.
        if (state.step != Step.CONFIRM || photo == null) return
        _uiState.update { it.copy(step = Step.SAVING) }
        viewModelScope.launch {
            val saved = try {
                store.save(photo)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "day plan not saved", e)
                false
            }
            if (!saved) {
                _uiState.update { it.copy(step = Step.CONFIRM, saveFailed = true).withEmergency() }
                return@launch
            }
            // The photo is kept; a log that fails must not bring the alarm back.
            runCatching { completionRepository.log(GoalKind.PLAN, CompletionStatus.VERIFIED, amount = 1) }
            _uiState.update { it.copy(step = Step.SUCCESS, photo = null, saveFailed = false) }
        }
    }

    fun logSkipped(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { completionRepository.log(GoalKind.PLAN, CompletionStatus.SKIPPED) }
            onDone()
        }
    }

    private companion object {
        const val TAG = "PlanVerify"
        const val EMERGENCY_AFTER_MS = 5 * 60_000L
    }
}
