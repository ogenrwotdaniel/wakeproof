package com.ogenrwotdaniel.wakeproof.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroPhase
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App-level state: whether first-launch onboarding has been completed (null = loading). */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {
    val onboardingComplete: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True while the phone should be pinned to this app: a focus countdown is
     * running and the "lock phone during focus" setting is on. Breaks, pause
     * and stop release the pin.
     */
    val focusLockActive: StateFlow<Boolean> =
        combine(PomodoroTimerService.state, settingsRepository.settings) { timer, cfg ->
            cfg.focusLockPhone && timer.active && timer.running &&
                timer.phase == PomodoroPhase.FOCUS
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True while the screen must not time out: the "keep screen awake" setting is
     * on and a countdown is running — **any** phase, breaks included, so the
     * screen never dies mid-session. [MainActivity] applies it to the whole
     * window, so it holds on every screen of the app — not only the Pomodoro one
     * — including when the timer was started and the user walked over to Home,
     * flashcards or the agenda. Only pause and stop release it.
     */
    val keepScreenOnActive: StateFlow<Boolean> =
        combine(PomodoroTimerService.state, settingsRepository.settings) { timer, cfg ->
            cfg.sessionKeepScreenOn && timer.active && timer.running
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
