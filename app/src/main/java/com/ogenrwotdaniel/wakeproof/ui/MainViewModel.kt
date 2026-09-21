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
     * True while the phone should be pinned to this app: a session is running
     * and the "lock phone during sessions" setting is on — **any** phase, breaks
     * included, so the pin lasts the whole session rather than one block. A
     * break used to hand the phone back every 25 minutes, which is how a
     * pomodoro turns into an hour of scrolling; the five minutes off are meant
     * to be five minutes off the work, not off the phone. Only pause and stop
     * release it, exactly like [keepScreenOnActive].
     *
     * [immersiveFocusActive] is deliberately NOT widened the same way. The pin
     * decides whether you can leave WakeProof; the full-screen timer decides
     * what WakeProof shows you, and a break with nothing on screen but a
     * countdown is a break in name only.
     */
    val focusLockActive: StateFlow<Boolean> =
        combine(PomodoroTimerService.state, settingsRepository.settings) { timer, cfg ->
            cfg.focusLockPhone && timer.active && timer.running
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True while the app should show the focus countdown and nothing else.
     *
     * Same condition as [focusLockActive] but its own setting, because the two
     * locks answer different complaints: the pin stops you leaving WakeProof,
     * this stops WakeProof itself offering you a study chat, a sound picker and
     * five other screens to go and reorganise mid-block. Drives the navigation
     * column as well as the Pomodoro screen, so a tablet does not keep a menu
     * on display through a countdown that is supposed to have hidden it.
     */
    val immersiveFocusActive: StateFlow<Boolean> =
        combine(PomodoroTimerService.state, settingsRepository.settings) { timer, cfg ->
            cfg.focusImmersiveTimer && timer.active && timer.running &&
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
