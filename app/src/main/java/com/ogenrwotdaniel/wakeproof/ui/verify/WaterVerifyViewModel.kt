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
 * Drives the two-photo water verification wizard: capture full → drink →
 * capture empty → Kimi verdict. Falls back to a 30s honor-mode hold when the
 * API is unreachable/unconfigured (logged UNVERIFIED), and opens the
 * emergency dismiss after 3 rejections (logged SKIPPED).
 */
@HiltViewModel
class WaterVerifyViewModel @Inject constructor(
    private val kimiRepository: KimiRepository,
    private val completionRepository: CompletionRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    enum class Step { CAPTURE_FULL, CONFIRM_FULL, CAPTURE_EMPTY, UPLOADING, SUCCESS, FALLBACK }

    data class UiState(
        val step: Step = Step.CAPTURE_FULL,
        val fullPhoto: ByteArray? = null,
        val rejectionReason: String? = null,
        val rejections: Int = 0,
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60,
        val waterAmountMl: Int = 500,
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

    fun onFullCaptured(bytes: ByteArray) {
        _uiState.update { it.copy(step = Step.CONFIRM_FULL, fullPhoto = bytes) }
    }

    fun onRetakeFull() {
        _uiState.update { it.copy(step = Step.CAPTURE_FULL, fullPhoto = null) }
    }

    fun onFullConfirmed() {
        _uiState.update { it.copy(step = Step.CAPTURE_EMPTY) }
    }

    fun onEmptyCaptured(bytes: ByteArray) {
        val full = _uiState.value.fullPhoto ?: run {
            _uiState.update { it.copy(step = Step.CAPTURE_FULL) }
            return
        }
        _uiState.update { it.copy(step = Step.UPLOADING, rejectionReason = null) }
        viewModelScope.launch {
            when (val result = kimiRepository.verifyWater(full, bytes)) {
                is KimiResult.Success -> {
                    val verdict = result.value
                    if (verdict.verified) onVerdictAccepted() else onVerdictRejected(verdict.reason, verdict)
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

    private suspend fun onVerdictAccepted() {
        runCatching {
            completionRepository.log(
                GoalKind.WATER, CompletionStatus.VERIFIED, amount = _uiState.value.waterAmountMl
            )
        }
        _uiState.update { it.copy(step = Step.SUCCESS) }
        val message = kimiRepository.motivationalMessage(
            "Drank ${_uiState.value.waterAmountMl} ml of water, camera-verified"
        )
        _uiState.update { it.copy(motivation = message) }
    }

    private fun onVerdictRejected(reason: String, verdict: com.ogenrwotdaniel.wakeproof.data.WaterVerdict) {
        val rejections = _uiState.value.rejections + 1
        // Photo-1/container problems restart the whole wizard; an unconvincing
        // "empty" photo only repeats the second shot.
        val backTo = if (!verdict.sameContainer || !verdict.photo1Full) Step.CAPTURE_FULL
        else Step.CAPTURE_EMPTY
        _uiState.update {
            it.copy(
                step = backTo,
                fullPhoto = if (backTo == Step.CAPTURE_FULL) null else it.fullPhoto,
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
