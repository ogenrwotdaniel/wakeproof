package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.plan.DayPlanPhoto
import com.ogenrwotdaniel.wakeproof.plan.DayPlanStore
import com.ogenrwotdaniel.wakeproof.plan.DayReview
import com.ogenrwotdaniel.wakeproof.plan.DayReviewStore
import com.ogenrwotdaniel.wakeproof.plan.PlanDone
import com.ogenrwotdaniel.wakeproof.plan.cleanTopThree
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the evening review: this morning's plan photo is shown, the user says
 * how much of it got done and writes tomorrow's top three. A plan that is
 * never checked against its day does little; this is the check.
 *
 * Nothing is judged: the answers are the user's own word. One thing for
 * tomorrow is enough to close the day; three are asked for.
 */
@HiltViewModel
class ShutdownVerifyViewModel @Inject constructor(
    private val plans: DayPlanStore,
    private val reviews: DayReviewStore,
    private val completionRepository: CompletionRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    data class UiState(
        /** Today's plan photo, the latest if several; null when none was taken. */
        val plan: DayPlanPhoto? = null,
        val planLooked: Boolean = false,
        val done: PlanDone? = null,
        val items: List<String> = listOf("", "", ""),
        val verified: Boolean = false,
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60
    ) {
        val canSave: Boolean get() = done != null && items.any { it.isNotBlank() }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val today = LocalDate.now().toEpochDay()
    private var saving = false

    init {
        viewModelScope.launch {
            val plan = runCatching { plans.list().firstOrNull { it.epochDay == today } }.getOrNull()
            // A review already written today is shown again to be changed, not lost.
            val earlier = reviews.load().reviews.firstOrNull { it.epochDay == today }
            _uiState.update {
                it.copy(
                    plan = plan,
                    planLooked = true,
                    done = earlier?.done,
                    items = earlier?.let { r -> (r.topThree + listOf("", "", "")).take(3) } ?: it.items
                )
            }
        }
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return@launch
            _uiState.update { it.copy(emergencyHoldSeconds = settings.emergencyHoldSeconds) }
        }
        viewModelScope.launch {
            delay(EMERGENCY_AFTER_MS)
            _uiState.update { it.copy(emergencyAvailable = true) }
        }
    }

    fun setDone(done: PlanDone) = _uiState.update { it.copy(done = done) }

    fun setItem(index: Int, text: String) = _uiState.update { state ->
        if (index !in state.items.indices) state
        else state.copy(items = state.items.toMutableList().also { it[index] = text.take(MAX_ITEM_LENGTH) })
    }

    fun save() {
        val state = _uiState.value
        val done = state.done ?: return
        if (saving || state.verified || !state.canSave) return
        saving = true
        viewModelScope.launch {
            val top = cleanTopThree(state.items)
            reviews.save(DayReview(today, done, top, System.currentTimeMillis()))
            runCatching {
                completionRepository.log(GoalKind.SHUTDOWN, CompletionStatus.VERIFIED, amount = top.size)
            }
            _uiState.update { it.copy(verified = true) }
        }
    }

    fun logSkipped(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { completionRepository.log(GoalKind.SHUTDOWN, CompletionStatus.SKIPPED) }
            onDone()
        }
    }

    private companion object {
        const val EMERGENCY_AFTER_MS = 5 * 60_000L
        const val MAX_ITEM_LENGTH = 120
    }
}
