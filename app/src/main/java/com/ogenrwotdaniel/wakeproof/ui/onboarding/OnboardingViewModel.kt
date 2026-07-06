package com.ogenrwotdaniel.wakeproof.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityChecker
import com.ogenrwotdaniel.wakeproof.alarm.ReliabilityStatus
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Onboarding: re-checkable permission status + completion flag. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val checker: ReliabilityChecker,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _status = MutableStateFlow(checker.check())
    val status: StateFlow<ReliabilityStatus> = _status.asStateFlow()

    /** Called on every resume — the user bounces to system settings and back. */
    fun refresh() {
        _status.value = checker.check()
    }

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setOnboardingComplete(true)
            onDone()
        }
    }
}
