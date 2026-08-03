package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WaterVerdict
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.verify.Achievement
import com.ogenrwotdaniel.wakeproof.verify.MotivationSource
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
 * the emergency dismiss (logged SKIPPED). An unreachable/unconfigured API drops
 * to the 30 s honor-mode hold (logged UNVERIFIED).
 */
@HiltViewModel
class WaterVerifyViewModel @Inject constructor(
    private val kimiRepository: KimiRepository,
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
        val fallbackReason: String? = null
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

    fun onBeforeCaptured(bytes: ByteArray) {
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
        val before = _uiState.value.beforePhoto ?: run {
            _uiState.update { it.copy(step = Step.CAPTURE_BEFORE) }
            return
        }
        _uiState.update { it.copy(step = Step.UPLOADING, rejectionReason = null, progressNote = null) }
        viewModelScope.launch {
            val result = kimiRepository.verifyWater(before, bytes, _uiState.value.bottleCapacityMl)
            when (result) {
                is KimiResult.Success -> {
                    val verdict = result.value
                    if (verdict.accepted) onSipAccepted(verdict, afterPhoto = bytes)
                    else onVerdictRejected(verdict)
                }
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
                rejections = rejections,
                emergencyAvailable = rejections >= MAX_REJECTIONS
            )
        }
    }

    private fun enterFallback(reason: String) {
        _uiState.update { it.copy(step = Step.FALLBACK, fallbackReason = reason) }
    }

    private companion object {
        const val MAX_REJECTIONS = 3

        /**
         * Estimating a level off a photo is coarse, so land within this of the
         * target rather than chasing the last few millilitres forever.
         */
        const val TARGET_TOLERANCE_ML = 50
    }
}
