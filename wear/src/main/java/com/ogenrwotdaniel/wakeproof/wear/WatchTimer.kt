package com.ogenrwotdaniel.wakeproof.wear

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The watch timer's phases. Labels match the phone's `PomodoroPhase` so both read alike. */
enum class WatchPhase(val label: String) {
    FOCUS("Focus"),
    SHORT_BREAK("Short break"),
    LONG_BREAK("Long break")
}

/**
 * Phase lengths, taken from the phone's settings when it has ever pushed them
 * and the phone's own defaults otherwise — a watch that has never met the phone
 * still has to be able to run a pomodoro.
 */
data class WatchDurations(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15
) {
    fun millisOf(phase: WatchPhase): Long = 60_000L * when (phase) {
        WatchPhase.FOCUS -> focusMinutes
        WatchPhase.SHORT_BREAK -> shortBreakMinutes
        WatchPhase.LONG_BREAK -> longBreakMinutes
    }.coerceAtLeast(1)
}

/**
 * The whole state of a pomodoro run on the watch, phone or no phone.
 *
 * Times are WALL CLOCK, not elapsedRealtime, unlike the phone's ticker: this
 * state is persisted and has to mean the same thing after a reboot, which
 * resets elapsedRealtime to zero. The cost is that a clock change mid-block
 * stretches or shortens that block — acceptable for a timer, not for storage.
 */
data class WatchTimerState(
    val active: Boolean = false,
    val running: Boolean = false,
    val phase: WatchPhase = WatchPhase.FOCUS,
    /** When the current phase ends; meaningful while [running]. */
    val phaseEndsAt: Long = 0L,
    /** Time left in the phase; meaningful while paused. */
    val remainingMillis: Long = 0L,
    val completedFocus: Int = 0,
    /** When the current focus block began, for the synced record. */
    val focusStartedAt: Long = 0L,
    val durations: WatchDurations = WatchDurations(),
    /** The phone's active study subject at start, carried onto every block. */
    val subject: String? = null,
    /** Focus blocks this session runs before stopping itself; 0 = free run until stopped. */
    val targetFocusBlocks: Int = 0
) {
    fun remainingMillisAt(now: Long): Long =
        if (running) (phaseEndsAt - now).coerceAtLeast(0L) else remainingMillis

    /** Rounded UP, like the phone's countdown: "0:01" until the phase is really over. */
    fun remainingSecondsAt(now: Long): Long = (remainingMillisAt(now) + 999) / 1000

    fun isDue(now: Long): Boolean = active && running && now >= phaseEndsAt
}

/**
 * One finished focus block waiting to reach the phone.
 *
 * [id] becomes the phone's `CompletionLog.syncId`, which is what makes delivery
 * idempotent end to end: the Data Layer may redeliver, and the same row may also
 * arrive on the tablet from Firestore, and every copy collapses onto one row.
 */
data class FocusBlock(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val minutes: Int,
    val epochDay: Long,
    val subject: String?
)

/**
 * The result of a phase ending: the next state, the focus block it completed (if
 * any), and whether that block was the last of a timed session.
 */
data class Advance(
    val state: WatchTimerState,
    val completed: FocusBlock?,
    val sessionFinished: Boolean = false
)

/** The session lengths offered on the watch — the same four the phone offers. */
enum class WatchSession(val label: String, val minutes: Int) {
    FREE("Free focus", 0),
    ONE_HOUR("1 hour", 60),
    NINETY("1 h 30 min", 90),
    TWO_HOURS("2 hours", 120)
}

/**
 * Pure transitions for the watch timer — no clock, no Android, unit-tested.
 * `WatchTimerEngine` applies them and does the side effects.
 */
object WatchTimer {

    /** Same cadence as the phone: a long break after every fourth focus block. */
    const val FOCUS_BLOCKS_PER_LONG_BREAK = 4

    fun start(
        durations: WatchDurations,
        subject: String?,
        now: Long,
        session: WatchSession = WatchSession.FREE
    ) = WatchTimerState(
        active = true,
        running = true,
        phase = WatchPhase.FOCUS,
        phaseEndsAt = now + durations.millisOf(WatchPhase.FOCUS),
        focusStartedAt = now,
        durations = durations,
        subject = subject,
        targetFocusBlocks = focusBlocksForSession(
            session.minutes, durations.focusMinutes, durations.shortBreakMinutes
        )
    )

    /**
     * How many focus blocks a timed session runs: the whole number whose real
     * length (k focus + (k-1) breaks — a session ends on focus, no trailing
     * break) lands closest to what was asked for. 0 for a free run.
     *
     * A COPY of the phone's `pomodoro/focusBlocksForSession` — no shared module,
     * so keep the two in step by hand, like the Data Layer contracts.
     */
    fun focusBlocksForSession(sessionMinutes: Int, focusMinutes: Int, breakMinutes: Int): Int {
        if (sessionMinutes <= 0) return 0
        val cycle = focusMinutes + breakMinutes
        if (cycle <= 0) return 1
        return ((sessionMinutes + breakMinutes + cycle / 2) / cycle).coerceAtLeast(1)
    }

    fun pause(state: WatchTimerState, now: Long): WatchTimerState =
        if (!state.active || !state.running) state
        else state.copy(running = false, remainingMillis = state.remainingMillisAt(now))

    fun resume(state: WatchTimerState, now: Long): WatchTimerState =
        if (!state.active || state.running) state
        else state.copy(running = true, phaseEndsAt = now + state.remainingMillis)

    /** A stop mid-focus credits nothing, as on the phone: only whole blocks count. */
    fun stop(): WatchTimerState = WatchTimerState()

    /** Ends a break early and starts the next focus block. A focus block cannot be skipped. */
    fun skipBreak(state: WatchTimerState, now: Long): WatchTimerState =
        if (!state.active || state.phase == WatchPhase.FOCUS) state
        else focusFrom(state, now)

    /**
     * Ends the current phase, if it is due.
     *
     * The next phase starts at [now], not at the scheduled end. A late alarm
     * (Doze, a dead watch) would otherwise start the next phase already partly
     * or wholly spent, and a catch-up loop over a long gap would credit focus
     * blocks nobody sat through. One firing ends exactly one phase.
     */
    fun advance(state: WatchTimerState, now: Long, zone: ZoneId, newId: () -> String = ::randomId): Advance {
        if (!state.isDue(now)) return Advance(state, null)
        if (state.phase != WatchPhase.FOCUS) return Advance(focusFrom(state, now), null)

        val endedAt = state.phaseEndsAt
        val block = FocusBlock(
            id = newId(),
            startedAt = state.focusStartedAt,
            endedAt = endedAt,
            minutes = state.durations.focusMinutes,
            epochDay = epochDayOf(endedAt, zone),
            subject = state.subject
        )
        val done = state.completedFocus + 1
        // A timed session ends on its last focus block; its trailing break is
        // never run, as on the phone.
        if (state.targetFocusBlocks in 1..done) {
            return Advance(stop(), block, sessionFinished = true)
        }
        val next = if (done % FOCUS_BLOCKS_PER_LONG_BREAK == 0) WatchPhase.LONG_BREAK
        else WatchPhase.SHORT_BREAK
        return Advance(
            state.copy(
                phase = next,
                phaseEndsAt = now + state.durations.millisOf(next),
                remainingMillis = 0L,
                completedFocus = done
            ),
            block
        )
    }

    /** The day a block counts towards is the day it ENDED, in the watch's zone. */
    fun epochDayOf(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun focusFrom(state: WatchTimerState, now: Long) = state.copy(
        running = true,
        phase = WatchPhase.FOCUS,
        phaseEndsAt = now + state.durations.millisOf(WatchPhase.FOCUS),
        remainingMillis = 0L,
        focusStartedAt = now
    )

    private fun randomId(): String = UUID.randomUUID().toString()
}
