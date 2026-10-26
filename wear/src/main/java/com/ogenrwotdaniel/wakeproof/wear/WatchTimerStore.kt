package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import androidx.core.content.edit

/**
 * Persists [WatchTimerState] so the timer outlives its process. The phase end
 * is delivered by an alarm to a receiver in a cold process, and the screen that
 * started the block is long gone by then.
 *
 * Plain SharedPreferences with `commit`, not `apply`: the receiver that writes
 * here returns straight afterwards, and a write still queued when the process is
 * reaped is a pomodoro the watch forgets it was running.
 */
internal object WatchTimerStore {

    private const val FILE = "watch_timer"
    private const val ACTIVE = "active"
    private const val RUNNING = "running"
    private const val PHASE = "phase"
    private const val ENDS_AT = "ends_at"
    private const val REMAINING = "remaining"
    private const val COMPLETED = "completed"
    private const val FOCUS_STARTED = "focus_started"
    private const val FOCUS_MIN = "focus_min"
    private const val SHORT_MIN = "short_min"
    private const val LONG_MIN = "long_min"
    private const val SUBJECT = "subject"
    private const val TARGET = "target_blocks"
    private const val LONG_EVERY = "long_break_every"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context): WatchTimerState {
        val p = prefs(context)
        if (!p.getBoolean(ACTIVE, false)) return WatchTimerState()
        val defaults = WatchDurations()
        return WatchTimerState(
            active = true,
            running = p.getBoolean(RUNNING, false),
            // An unknown name is a newer build's phase; FOCUS is the safe reading.
            phase = WatchPhase.entries.firstOrNull { it.name == p.getString(PHASE, null) }
                ?: WatchPhase.FOCUS,
            phaseEndsAt = p.getLong(ENDS_AT, 0L),
            remainingMillis = p.getLong(REMAINING, 0L),
            completedFocus = p.getInt(COMPLETED, 0),
            focusStartedAt = p.getLong(FOCUS_STARTED, 0L),
            durations = WatchDurations(
                focusMinutes = p.getInt(FOCUS_MIN, defaults.focusMinutes),
                shortBreakMinutes = p.getInt(SHORT_MIN, defaults.shortBreakMinutes),
                longBreakMinutes = p.getInt(LONG_MIN, defaults.longBreakMinutes)
            ),
            subject = p.getString(SUBJECT, null),
            targetFocusBlocks = p.getInt(TARGET, 0),
            longBreakEvery = p.getInt(LONG_EVERY, WatchTimer.FOCUS_BLOCKS_PER_LONG_BREAK)
        )
    }

    fun write(context: Context, state: WatchTimerState) {
        prefs(context).edit(commit = true) {
            clear()
            if (!state.active) return@edit
            putBoolean(ACTIVE, true)
            putBoolean(RUNNING, state.running)
            putString(PHASE, state.phase.name)
            putLong(ENDS_AT, state.phaseEndsAt)
            putLong(REMAINING, state.remainingMillis)
            putInt(COMPLETED, state.completedFocus)
            putLong(FOCUS_STARTED, state.focusStartedAt)
            putInt(FOCUS_MIN, state.durations.focusMinutes)
            putInt(SHORT_MIN, state.durations.shortBreakMinutes)
            putInt(LONG_MIN, state.durations.longBreakMinutes)
            putString(SUBJECT, state.subject)
            putInt(TARGET, state.targetFocusBlocks)
            putInt(LONG_EVERY, state.longBreakEvery)
        }
    }
}
