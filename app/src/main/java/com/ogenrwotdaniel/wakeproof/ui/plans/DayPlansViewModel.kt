package com.ogenrwotdaniel.wakeproof.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.plan.DayPlanPhoto
import com.ogenrwotdaniel.wakeproof.plan.DayPlanStore
import com.ogenrwotdaniel.wakeproof.plan.DayReview
import com.ogenrwotdaniel.wakeproof.plan.DayReviewStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The saved day-plan photos, newest first; null until the first read. */
@HiltViewModel
class DayPlansViewModel @Inject constructor(
    private val store: DayPlanStore,
    reviewStore: DayReviewStore
) : ViewModel() {

    /** How each day's plan went, by day, from the evening reviews. */
    val reviews: StateFlow<Map<Long, DayReview>> = reviewStore.reviews
        .map { all -> all.reviews.associateBy { it.epochDay } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch { reviewStore.load() }
    }

    val plans: StateFlow<List<DayPlanPhoto>?> = store.revision
        // A folder that cannot be read (storage still locked) shows as empty.
        .map { runCatching { store.list() }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(photo: DayPlanPhoto) {
        viewModelScope.launch { runCatching { store.delete(photo) } }
    }
}
