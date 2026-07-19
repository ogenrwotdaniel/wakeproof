package com.ogenrwotdaniel.wakeproof

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide flag for whether the app UI is visible. Updated by
 * [MainActivity]; the floating Pomodoro bubble hides while the app itself is
 * on screen.
 */
object AppForeground {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun set(visible: Boolean) {
        _visible.value = visible
    }
}
