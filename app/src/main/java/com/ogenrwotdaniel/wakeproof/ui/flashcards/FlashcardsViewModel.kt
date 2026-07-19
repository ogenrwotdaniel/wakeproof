package com.ogenrwotdaniel.wakeproof.ui.flashcards

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.FlashcardDraft
import com.ogenrwotdaniel.wakeproof.data.FlashcardRepository
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.ReviewGrade
import com.ogenrwotdaniel.wakeproof.data.db.Flashcard
import com.ogenrwotdaniel.wakeproof.data.db.SubjectCardCount
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Flashcards screen state: deck list, an active review run, or AI drafts. */
@HiltViewModel
class FlashcardsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FlashcardRepository,
    private val kimiRepository: KimiRepository,
    keyStore: ApiKeyStore
) : ViewModel() {

    val hasApiKey: Boolean = keyStore.hasKey()

    val subjects: StateFlow<List<SubjectCardCount>> = repository.observeSubjectCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A running review session; null = showing the deck list. */
    data class ReviewState(
        val subject: String,
        val queue: List<Flashcard>,
        val revealed: Boolean = false,
        val gradedCount: Int = 0,
        val totalAtStart: Int = queue.size
    )

    private val _review = MutableStateFlow<ReviewState?>(null)
    val review: StateFlow<ReviewState?> = _review.asStateFlow()

    /** AI-generation state for the add-cards flow. */
    data class DraftState(
        val generating: Boolean = false,
        val drafts: List<FlashcardDraft> = emptyList(),
        val error: String? = null
    )

    private val _drafts = MutableStateFlow(DraftState())
    val drafts: StateFlow<DraftState> = _drafts.asStateFlow()

    fun startReview(subject: String) {
        viewModelScope.launch {
            val due = repository.dueCards(subject)
            if (due.isNotEmpty()) _review.value = ReviewState(subject, due)
        }
    }

    fun reveal() = _review.update { it?.copy(revealed = true) }

    fun endReview() {
        _review.value = null
    }

    /** AGAIN re-queues the card at the back so it repeats this session. */
    fun grade(grade: ReviewGrade) {
        val state = _review.value ?: return
        val card = state.queue.firstOrNull() ?: return
        viewModelScope.launch {
            repository.review(card, grade)
            val rest = state.queue.drop(1)
            val nextQueue = if (grade == ReviewGrade.AGAIN) rest + card else rest
            _review.value =
                if (nextQueue.isEmpty()) null
                else state.copy(
                    queue = nextQueue,
                    revealed = false,
                    gradedCount = state.gradedCount + 1
                )
        }
    }

    fun addManualCard(subject: String, front: String, back: String) {
        if (subject.isBlank() || front.isBlank() || back.isBlank()) return
        viewModelScope.launch { repository.addCards(subject, listOf(front to back)) }
    }

    fun generateFromText(text: String) {
        if (text.isBlank()) return
        generate { kimiRepository.generateFlashcards(sourceText = text, notesPhoto = null) }
    }

    fun generateFromPhoto(uri: Uri) {
        generate {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return@generate KimiResult.ApiError(null, "Could not read the selected photo")
            kimiRepository.generateFlashcards(sourceText = null, notesPhoto = bytes)
        }
    }

    private fun generate(block: suspend () -> KimiResult<List<FlashcardDraft>>) {
        if (_drafts.value.generating) return
        _drafts.value = DraftState(generating = true)
        viewModelScope.launch {
            _drafts.value = when (val result = block()) {
                is KimiResult.Success ->
                    if (result.value.isEmpty()) DraftState(error = "No cards could be drafted.")
                    else DraftState(drafts = result.value)
                is KimiResult.ApiError -> DraftState(error = "AI error: ${result.message}")
                KimiResult.NetworkError -> DraftState(error = "Network error — try again.")
                KimiResult.NoKey -> DraftState(error = "Add an API key in Settings first.")
            }
        }
    }

    fun removeDraft(draft: FlashcardDraft) =
        _drafts.update { it.copy(drafts = it.drafts - draft) }

    fun discardDrafts() {
        _drafts.value = DraftState()
    }

    fun saveDrafts(subject: String) {
        val toSave = _drafts.value.drafts
        if (subject.isBlank() || toSave.isEmpty()) return
        viewModelScope.launch {
            repository.addCards(subject, toSave.map { it.front to it.back })
            _drafts.value = DraftState()
        }
    }

    fun deleteSubject(subject: String) {
        viewModelScope.launch { repository.deleteSubject(subject) }
    }
}
