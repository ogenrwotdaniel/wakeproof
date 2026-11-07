package com.ogenrwotdaniel.wakeproof.ui.verify

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WaterVerdict
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.verify.Achievement
import com.ogenrwotdaniel.wakeproof.verify.MotivationSource
import com.ogenrwotdaniel.wakeproof.verify.water.WaterObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives water verification as a *running tally* rather than one all-or-nothing
 * shot: photograph the bottle → drink → photograph it again. Each accepted pair
 * credits the millilitres the level actually fell (read as a fraction of the
 * bottle, so no printed ml scale is needed) and the last "after" photo becomes
 * the next "before". Keep drinking and re-shooting until the credited total
 * reaches the per-alarm target; a couple of mouthfuls at a time is fine.
 *
 * Rejections are reserved for genuine failures — a different bottle, an
 * unreadable water line, a refill, no measurable drop — and three of them open
 * the emergency dismiss (logged SKIPPED).
 *
 * The comparison itself comes from a [WaterObserver], which reads the level on
 * the device and only consults a vision model for a second opinion before
 * showing a rejection. The 30 s honor-mode hold (logged UNVERIFIED) is now
 * reached only when the photos cannot be decoded at all, or when on-device
 * verification has been switched off in Settings and the cloud is unreachable.
 */
@HiltViewModel
class WaterVerifyViewModel @Inject constructor(
    private val waterObserver: WaterObserver,
    private val completionRepository: CompletionRepository,
    private val motivationSource: MotivationSource,
    settingsRepository: SettingsRepository
) : ViewModel() {

    enum class Step { CAPTURE_BEFORE, CONFIRM_BEFORE, CAPTURE_AFTER, UPLOADING, SUCCESS, FALLBACK }

    data class UiState(
        val step: Step = Step.CAPTURE_BEFORE,
        val beforePhoto: ByteArray? = null,
        val rejectionReason: String? = null,
        val rejections: Int = 0,
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60,
        val waterAmountMl: Int = 500,
        val bottleCapacityMl: Int = 750,
        /** Credited so far this session, across every accepted photo pair. */
        val consumedMl: Int = 0,
        /** Encouragement after a partial gulp; not an error. */
        val progressNote: String? = null,
        val motivation: String? = null,
        val fallbackReason: String? = null,
        /** The camera failed to start or to take a photo; offered a retry. */
        val cameraFailed: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _uiState.update {
                it.copy(
                    emergencyHoldSeconds = settings.emergencyHoldSeconds,
                    waterAmountMl = settings.waterAmountMl,
                    bottleCapacityMl = settings.bottleCapacityMl
                )
            }
        }
    }

    private val permission = CameraPermissionLatch()
    private var timedOut = false

    init {
        // The promised exit for a camera that fails without saying so: a photo
        // queued on it neither arrives nor errors, so nothing else would open this.
        viewModelScope.launch {
            delay(EMERGENCY_AFTER_MS)
            timedOut = true
            _uiState.update { it.withEmergency() }
        }
    }

    /** No photo can be taken, so waiting for rejections would leave no way out. */
    fun onCameraUnavailable() {
        _uiState.update { it.copy(cameraFailed = true).withEmergency() }
    }

    /** The user asked to try again; the hatch closes unless something else opened it. */
    fun retryCamera() = onCameraWorking()

    fun onCameraPermission(granted: Boolean) =
        permission.report(granted, onLost = ::onCameraUnavailable, onBack = ::onCameraWorking)

    /** The one place the hatch is decided. */
    private fun UiState.withEmergency() =
        copy(emergencyAvailable = rejections >= MAX_REJECTIONS || cameraFailed || timedOut)

    /** A brief fault must not leave the alarm dismissible without any attempt. */
    private fun onCameraWorking() {
        if (!_uiState.value.cameraFailed) return
        _uiState.update { it.copy(cameraFailed = false).withEmergency() }
    }

    fun onBeforeCaptured(bytes: ByteArray) {
        if (_uiState.value.step != Step.CAPTURE_BEFORE) return
        onCameraWorking()
        _uiState.update { it.copy(step = Step.CONFIRM_BEFORE, beforePhoto = bytes) }
    }

    fun onRetakeBefore() {
        _uiState.update { it.copy(step = Step.CAPTURE_BEFORE, beforePhoto = null) }
    }

    fun onBeforeConfirmed() {
        _uiState.update { it.copy(step = Step.CAPTURE_AFTER) }
    }

    /**
     * The bottle ran dry before the target was met, so it had to be topped up.
     * Safe to allow: only *drops* from a baseline are ever credited, so refilling
     * can't earn millilitres — it just needs a fresh baseline photo. The tally
     * already earned is kept.
     */
    fun onRefilled() {
        _uiState.update {
            it.copy(
                step = Step.CAPTURE_BEFORE,
                beforePhoto = null,
                rejectionReason = null,
                progressNote = "Refilled — take a new baseline photo. " +
                    "Your ${it.consumedMl} ml so far still counts."
            )
        }
    }

    fun onAfterCaptured(bytes: ByteArray) {
        // A double tap queues two photos; the second must not credit the sip again.
        if (_uiState.value.step != Step.CAPTURE_AFTER) return
        onCameraWorking()
        val before = _uiState.value.beforePhoto ?: run {
            _uiState.update { it.copy(step = Step.CAPTURE_BEFORE) }
            return
        }
        _uiState.update { it.copy(step = Step.UPLOADING, rejectionReason = null, progressNote = null) }
        viewModelScope.launch {
            // Thrown from here it would kill the process with the alarm ringing,
            // and everything earned so far with it.
            val result = try {
                waterObserver.observe(before, bytes, _uiState.value.bottleCapacityMl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "water check failed", e)
                // A strike like any rejection, not the honour-mode exit: one bad
                // photo must not be the easy way out.
                onPhotoUnreadable()
                return@launch
            } catch (e: OutOfMemoryError) {
                // Two full photos decoded at once on a low-memory phone.
                Log.w(TAG, "water check ran out of memory", e)
                onPhotoUnreadable()
                return@launch
            }
            when (result) {
                is KimiResult.Success -> {
                    val verdict = result.value
                    if (verdict.accepted) onSipAccepted(verdict, afterPhoto = bytes)
                    else onVerdictRejected(verdict)
                }
                // Only reachable with on-device verification switched off: the
                // default path answers from the device and never needs a key,
                // which is what retired the honour-mode hold.
                KimiResult.NoKey -> enterFallback("No AI provider key configured.")
                KimiResult.NetworkError -> enterFallback("No network connection.")
                is KimiResult.ApiError -> enterFallback("Verifier error: ${result.message}")
            }
        }
    }

    /** Honor-mode hold completed: counts, but marked unverified in stats. */
    fun logHonorMode(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                completionRepository.log(
                    GoalKind.WATER, CompletionStatus.UNVERIFIED, amount = _uiState.value.waterAmountMl
                )
            }
            onDone()
        }
    }

    fun logSkipped(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { completionRepository.log(GoalKind.WATER, CompletionStatus.SKIPPED) }
            onDone()
        }
    }

    /**
     * A real drop was measured. Credit it; if the tally still falls short of the
     * target, keep the wizard open with this photo as the new baseline so the next
     * comparison only has to prove the *next* gulp.
     */
    private suspend fun onSipAccepted(verdict: WaterVerdict, afterPhoto: ByteArray) {
        val total = _uiState.value.consumedMl + verdict.consumedMl
        val target = _uiState.value.waterAmountMl
        if (total + TARGET_TOLERANCE_ML < target) {
            _uiState.update {
                it.copy(
                    step = Step.CAPTURE_AFTER,
                    // Chain from here: this shot is the baseline for the next gulp.
                    beforePhoto = afterPhoto,
                    consumedMl = total,
                    progressNote = "+${verdict.consumedMl} ml — $total of $target ml. " +
                        "Keep drinking, then shoot the same bottle again."
                )
            }
            return
        }
        runCatching {
            completionRepository.log(GoalKind.WATER, CompletionStatus.VERIFIED, amount = total)
        }
        _uiState.update { it.copy(step = Step.SUCCESS, consumedMl = total, progressNote = null) }
        val message = runCatching {
            motivationSource.motivationalMessage(Achievement.Water(total))
        }.getOrNull()
        _uiState.update { it.copy(motivation = message) }
    }

    private fun onVerdictRejected(verdict: WaterVerdict) {
        // A spotted refill on the same bottle isn't cheating — nothing is credited
        // for it — so it costs a new baseline photo, not a strike.
        if (verdict.refilled && verdict.sameContainer) {
            onRefilled()
            return
        }
        val rejections = _uiState.value.rejections + 1
        // A different or unreadable bottle invalidates the baseline, so the wizard
        // restarts; a readable bottle that simply didn't empty enough only needs
        // another "after" shot once more water is gone.
        val restart = !verdict.sameContainer || !verdict.levelReadable
        val reason = verdict.reason.ifBlank {
            when {
                !verdict.sameContainer -> "That's not the same bottle."
                !verdict.levelReadable -> "Can't see the water line — try better light and an angle where the level shows."
                verdict.refilled -> "The bottle was refilled between the photos."
                else -> "The level didn't drop enough to measure."
            }
        }
        _uiState.update {
            it.copy(
                step = if (restart) Step.CAPTURE_BEFORE else Step.CAPTURE_AFTER,
                beforePhoto = if (restart) null else it.beforePhoto,
                rejectionReason = reason,
                progressNote = null,
                rejections = rejections
            ).withEmergency()
        }
    }

    private fun onPhotoUnreadable() {
        val rejections = _uiState.value.rejections + 1
        _uiState.update {
            it.copy(
                step = Step.CAPTURE_BEFORE,
                beforePhoto = null,
                rejectionReason = "That photo couldn't be read. Take both photos again.",
                progressNote = null,
                rejections = rejections
            ).withEmergency()
        }
    }

    private fun enterFallback(reason: String) {
        _uiState.update { it.copy(step = Step.FALLBACK, fallbackReason = reason) }
    }

    private companion object {
        const val TAG = "WaterVerify"
        const val MAX_REJECTIONS = 3
        const val EMERGENCY_AFTER_MS = 5 * 60_000L

        /**
         * Estimating a level off a photo is coarse, so land within this of the
         * target rather than chasing the last few millilitres forever.
         */
        const val TARGET_TOLERANCE_ML = 50
    }
}
