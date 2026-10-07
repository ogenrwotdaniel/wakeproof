package com.ogenrwotdaniel.wakeproof.ui.forest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.ForestDay
import com.ogenrwotdaniel.wakeproof.data.forestDays
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The last four weeks of focus blocks, as trees. */
@HiltViewModel
class ForestViewModel @Inject constructor(
    completionRepository: CompletionRepository
) : ViewModel() {

    val days: StateFlow<List<ForestDay>> = completionRepository
        .observeSince(LocalDate.now().toEpochDay() - (DAYS - 1))
        .map { forestDays(it, LocalDate.now().toEpochDay(), DAYS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val DAYS = 28
    }
}
