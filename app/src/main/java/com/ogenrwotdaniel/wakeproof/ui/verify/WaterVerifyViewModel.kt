package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the two-photo water verification wizard: photograph the bottle's
 * water line against its printed ml scale (before) → drink some → photograph
 * it again (after) → the model reads both levels and the drop is logged as the
 * amount consumed, on any bottle size. Falls back to a 30s honor-mode hold when
 * the API is unreachable/unconfigured (logged UNVERIFIED), and opens the
 * emergency dismiss after 3 rejections (logged SKIPPED).
 */
@HiltViewModel
class WaterVerifyViewModel @Inject constructor(
    private val kimiRepository: KimiRepository,
    private val completionRepository: CompletionRepository,
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
        val consumedMl: Int = 0,
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
                    waterAmountMl = settings.waterAmountMl
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

    fun onAfterCaptured(bytes: ByteArray) {
        val before = _uiState.value.beforePhoto ?: run {
            _uiState.update { it.copy(step = Step.CAPTURE_BEFORE) }
            return
        }
        _uiState.update { it.copy(step = Step.UPLOADING, rejectionReason = null) }
        viewModelScope.launch {
            val result = kimiRepository.verifyWater(before, bytes, _uiState.value.waterAmountMl)
            when (result) {
                is KimiResult.Success -> {
                    val verdict = result.value
                    if (verdict.verified) onVerdictAccepted(verdict) else onVerdictRejected(verdict.reason, verdict)
                }
                KimiResult.NoKey -> enterFallback("No Kimi API key configured.")
                KimiResult.NetworkError -> enterFallback("No network connection.")
                is KimiResult.ApiError -> enterFallback("Kimi API error: ${result.message}")
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

    private suspend fun onVerdictAccepted(verdict: com.ogenrwotdaniel.wakeproof.data.WaterVerdict) {
        val consumed = verdict.consumedMl
        runCatching {
            completionRepository.log(
                GoalKind.WATER, CompletionStatus.VERIFIED, amount = consumed
            )
        }
        _uiState.update { it.copy(step = Step.SUCCESS, consumedMl = consumed) }
        val message = kimiRepository.motivationalMessage(
            "Drank $consumed ml of water, camera-verified against the bottle's ml marks"
        )
        _uiState.update { it.copy(motivation = message) }
    }

    private fun onVerdictRejected(reason: String, verdict: com.ogenrwotdaniel.wakeproof.data.WaterVerdict) {
        val rejections = _uiState.value.rejections + 1
        // A wrong/unreadable bottle restarts the whole wizard; a valid bottle
        // whose level just didn't drop enough only repeats the "after" shot.
        val backTo = if (!verdict.sameContainer || !verdict.scaleVisible) Step.CAPTURE_BEFORE
        else Step.CAPTURE_AFTER
        _uiState.update {
            it.copy(
                step = backTo,
                beforePhoto = if (backTo == Step.CAPTURE_BEFORE) null else it.beforePhoto,
                rejectionReason = reason.ifBlank { "Rejected by the verifier." },
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
    }
}
