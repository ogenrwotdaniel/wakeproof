package com.ogenrwotdaniel.wakeproof.ui.verify

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.EXAM_RAMP_DAYS
import com.ogenrwotdaniel.wakeproof.data.ExamDay
import com.ogenrwotdaniel.wakeproof.data.db.ExamDao
import com.ogenrwotdaniel.wakeproof.data.reviewCardsOn
import com.ogenrwotdaniel.wakeproof.verify.review.examCardsFirst
import java.time.LocalDate
import com.ogenrwotdaniel.wakeproof.data.FlashcardRepository
import com.ogenrwotdaniel.wakeproof.data.ReviewGrade
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.verify.review.REVIEW_ALARM_CARDS
import com.ogenrwotdaniel.wakeproof.verify.review.ReviewRun
import com.ogenrwotdaniel.wakeproof.verify.review.graded
import com.ogenrwotdaniel.wakeproof.verify.review.reviewRunOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the review mission: up to [REVIEW_ALARM_CARDS] due flashcards, each
 * graded the way the Flashcards screen grades them, so the alarm does the
 * day's spaced repetition instead of leaving it to be remembered.
 *
 * Grading is the user's own word; nothing checks the answer. What the alarm
 * adds is that the cards are put in front of them every day.
 *
 * With no card due, or cards that cannot be read (Room before the first
 * unlock), [UiState.noCards] sends the screen to the sums instead.
 */
@HiltViewModel
class ReviewVerifyViewModel @Inject constructor(
    private val flashcards: FlashcardRepository,
    private val completionRepository: CompletionRepository,
    private val examDao: ExamDao,
    settingsRepository: SettingsRepository
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val run: ReviewRun = ReviewRun(emptyList(), 0),
        val revealed: Boolean = false,
        /**
         * When the card on screen was put there ([SystemClock.elapsedRealtime]).
         * Held here so the think-first wait survives a rotation instead of
         * starting again.
         */
        val cardShownAt: Long = 0L,
        val noCards: Boolean = false,
        val verified: Boolean = false,
        val emergencyAvailable: Boolean = false,
        val emergencyHoldSeconds: Int = 60
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** A grade is being stored; a second tap must not grade the next card unseen. */
    private var grading = false

    init {
        viewModelScope.launch {
            val today = LocalDate.now().toEpochDay()
            // No exams read (locked storage) is the ordinary run, not a failure.
            val exams = runCatching { examDao.observeUpcoming(today).first() }.getOrDefault(emptyList())
                .map { ExamDay(it.name, it.epochDay) }
            val near = exams.filter { it.epochDay - today <= EXAM_RAMP_DAYS }.map { it.name }
            val due = runCatching { flashcards.dueCardsAnySubject(DUE_POOL) }
                .getOrDefault(emptyList())
            val run = reviewRunOf(examCardsFirst(due, near), reviewCardsOn(today, exams, REVIEW_ALARM_CARDS))
            _uiState.update {
                it.copy(
                    loading = false, run = run, noCards = run.finished,
                    cardShownAt = SystemClock.elapsedRealtime()
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

    fun reveal() = _uiState.update { if (it.run.current == null) it else it.copy(revealed = true) }

    fun grade(grade: ReviewGrade) {
        val state = _uiState.value
        val card = state.run.current ?: return
        if (grading || !state.revealed || state.verified) return
        grading = true
        viewModelScope.launch {
            // A card that cannot be stored still counts as answered: the alarm
            // must not hang on a database write.
            val stored = runCatching { flashcards.review(card, grade) }.getOrDefault(card)
            val next = state.run.graded(grade, stored)
            _uiState.update {
                it.copy(
                    run = next, revealed = false, verified = next.finished,
                    cardShownAt = SystemClock.elapsedRealtime()
                )
            }
            grading = false
            if (next.finished) {
                runCatching {
                    completionRepository.log(GoalKind.REVIEW, CompletionStatus.VERIFIED, amount = next.required)
                }
            }
        }
    }

    fun logSkipped(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { completionRepository.log(GoalKind.REVIEW, CompletionStatus.SKIPPED) }
            onDone()
        }
    }

    private companion object {
        const val EMERGENCY_AFTER_MS = 5 * 60_000L

        /** Due cards read before the exam subjects are put first. */
        const val DUE_POOL = 200
    }
}
