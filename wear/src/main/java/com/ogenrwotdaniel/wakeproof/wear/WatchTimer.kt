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
    /** Focus blocks this session runs before stopping itself. */
    val targetFocusBlocks: Int = 0,
    /** A long break follows every this-many focus blocks (3 in the 3-hour preset). */
    val longBreakEvery: Int = WatchTimer.FOCUS_BLOCKS_PER_LONG_BREAK,
    /**
     * The LAST focus block's length; the others run the focus duration.
     * Differs only in an event session ([WatchTimer.startExact]), whose last
     * block is trimmed or stretched so it ends with the event. Defaults to the
     * focus length, which is what every state stored before it means.
     */
    val lastFocusSeconds: Int = durations.focusMinutes.coerceAtLeast(1) * 60,
    /**
     * Names the session on every device that runs it (2026-11-11; the phone's
     * `SharedSession.id`), and so its blocks ([WatchTimer.blockId]). Blank in a
     * state stored before that.
     */
    val sessionId: String = "",
    /** Where the session started: here, or on the phone whose session this watch joined. */
    val origin: String = WatchTimer.ORIGIN_WATCH
) {
    /** Running a session the phone started: the phone keeps the time, so no break is skipped here. */
    val following: Boolean get() = origin != WatchTimer.ORIGIN_WATCH

    /** How long focus block [block] (1-based) runs; see [WatchTimer.focusSecondsForBlock]. */
    fun focusMillisOfBlock(block: Int): Long = 1_000L * WatchTimer.focusSecondsForBlock(
        block, targetFocusBlocks, (durations.millisOf(WatchPhase.FOCUS) / 1_000L).toInt(), lastFocusSeconds
    )

    /** Focus minutes in blocks 1..[blocks], for the end-of-session summary. */
    fun focusMinutesThrough(blocks: Int): Int =
        (1..blocks).sumOf { (focusMillisOfBlock(it) / 60_000L).toInt() }

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

/**
 * The phone's running session as it puts it on the timer item (2026-11-11), for
 * the watch to run too. A hand-kept COPY of the phone's `SharedSession`; read by
 * `TimerSnapshot`.
 */
data class PhoneSession(
    val sessionId: String,
    val origin: String,
    val phaseName: String,
    val phaseEndsAt: Long,
    val completedFocus: Int,
    val targetFocusBlocks: Int,
    val focusSeconds: Int,
    val shortBreakSeconds: Int,
    val longBreakSeconds: Int,
    val longBreakEvery: Int,
    val lastFocusSeconds: Int,
    val subject: String?
)

/**
 * The session lengths offered on the watch — the same four the phone offers.
 *
 * No free run, as on the phone (2026-10-27): a session has no pause and no stop
 * any more, so it must end by itself. [THREE_HOURS] replaced it and, like the
 * phone's, is a fixed recipe rather than a target: three 25-minute pomodoros,
 * a 15-minute break, three more — whatever the phone's length settings say.
 */
enum class WatchSession(val label: String, val minutes: Int) {
    ONE_HOUR("1 hour", 60),
    NINETY("1 h 30 min", 90),
    TWO_HOURS("2 hours", 120),
    THREE_HOURS("3 hours", 180)
}

/**
 * An event session's shape: [focusBlocks] of 25 minutes with 5-minute breaks
 * and no long break, the last one [lastFocusMinutes] long. A hand-kept COPY of
 * the phone's `pomodoro/SessionLength.kt` `exactSessionPlan` (which returns a
 * `SessionPlan`; only these two numbers vary). Pinned by the same vectors in
 * the phone's `ExactSessionTest` and the watch's `WatchTimerTest`.
 */
data class WatchExactPlan(val focusBlocks: Int, val lastFocusMinutes: Int)

/**
 * Pure transitions for the watch timer — no clock, no Android, unit-tested.
 * `WatchTimerEngine` applies them and does the side effects.
 */
object WatchTimer {

    /** Same cadence as the phone: a long break after every fourth focus block. */
    const val FOCUS_BLOCKS_PER_LONG_BREAK = 4

    /** A session started on this watch. MUST match the phone's `SharedSession.ORIGIN_WATCH`. */
    const val ORIGIN_WATCH = "watch"

    /**
     * The id of focus block [block] (1-based) of session [sessionId]: the same on
     * every device that ran the session, so a block finished on the watch and on
     * the phone is one row. A COPY of the phone's `sharedBlockId`.
     */
    fun blockId(sessionId: String, block: Int): String = "$sessionId-b$block"

    /**
     * The phone's session, run here too from where it is ([PhoneSession], the
     * watch's "Continue here"). Null when it cannot be: malformed, or its phase
     * all but over. Minutes, as the phone's settings are.
     */
    fun follow(session: PhoneSession, now: Long): WatchTimerState? {
        val phase = WatchPhase.entries.firstOrNull { it.name == session.phaseName } ?: return null
        if (!SESSION_ID.matches(session.sessionId)) return null
        if (session.targetFocusBlocks < 1 || session.completedFocus !in 0 until session.targetFocusBlocks) return null
        if (session.focusSeconds < 60 || session.shortBreakSeconds < 60 || session.longBreakSeconds < 60) return null
        if (session.phaseEndsAt - now < JOIN_MARGIN_MILLIS) return null
        return WatchTimerState(
            active = true,
            running = true,
            phase = phase,
            phaseEndsAt = session.phaseEndsAt,
            completedFocus = session.completedFocus,
            // Not known, and only the record's start time: the block's minutes are its full length.
            focusStartedAt = now,
            durations = WatchDurations(
                session.focusSeconds / 60, session.shortBreakSeconds / 60, session.longBreakSeconds / 60
            ),
            subject = session.subject,
            targetFocusBlocks = session.targetFocusBlocks,
            longBreakEvery = session.longBreakEvery.coerceAtLeast(1),
            lastFocusSeconds = session.lastFocusSeconds.takeIf { it >= 60 } ?: session.focusSeconds,
            sessionId = session.sessionId,
            origin = session.origin.ifBlank { "phone" }
        )
    }

    /** A COPY of the phone's `validSessionId` pattern and `JOIN_MARGIN_MILLIS`. */
    private val SESSION_ID = Regex("[A-Za-z0-9-]{8,64}")
    const val JOIN_MARGIN_MILLIS = 5_000L

    /** The 3-hour recipe's lengths. A COPY of the phone's `sessionPlan` — keep in step by hand. */
    val THREE_HOUR_DURATIONS = WatchDurations(25, 5, 15)
    const val THREE_HOUR_BLOCKS = 6
    const val THREE_HOUR_LONG_BREAK_EVERY = 3

    /** Focus and break lengths of an exact (event) session. COPY of the phone's `EXACT_*`. */
    const val EXACT_FOCUS_MINUTES = 25
    const val EXACT_BREAK_MINUTES = 5

    /** Shortest event session, and shortest last block. COPY of the phone's `MIN_EXACT_SESSION_MINUTES`. */
    const val MIN_EXACT_SESSION_MINUTES = 10

    /**
     * A session lasting EXACTLY [totalMinutes]: 25 + 5 blocks, the last one
     * trimmed — or, when trimming would leave a stub under
     * [MIN_EXACT_SESSION_MINUTES], stretched — so it ends on the minute.
     * A COPY of the phone's `exactSessionPlan`; keep the two in step by hand.
     */
    fun exactSessionPlan(totalMinutes: Int): WatchExactPlan {
        val total = totalMinutes.coerceAtLeast(MIN_EXACT_SESSION_MINUTES)
        val cycle = EXACT_FOCUS_MINUTES + EXACT_BREAK_MINUTES
        var blocks = ((total + EXACT_BREAK_MINUTES + cycle - 1) / cycle).coerceAtLeast(1)
        var last = total - (blocks - 1) * cycle
        if (last < MIN_EXACT_SESSION_MINUTES && blocks > 1) {
            blocks -= 1
            last += cycle
        }
        return WatchExactPlan(blocks, last)
    }

    /**
     * How long focus block [block] (1-based) runs in a session of
     * [targetFocusBlocks]: the last [lastFocusSeconds], the rest [focusSeconds].
     * A COPY of the phone's `focusSecondsForBlock`.
     */
    fun focusSecondsForBlock(block: Int, targetFocusBlocks: Int, focusSeconds: Int, lastFocusSeconds: Int): Int =
        if (targetFocusBlocks > 0 && block >= targetFocusBlocks) lastFocusSeconds else focusSeconds

    /**
     * Minutes a session for a calendar event from [beginMillis] to [endMillis]
     * runs if started at [now], or null when it cannot be focused for — over,
     * or under [MIN_EXACT_SESSION_MINUTES] left. Counted from now when the
     * event has begun, so the session ends with it. A COPY of the phone's
     * `agenda/eventFocusMinutes` (all-day events never reach the watch).
     */
    fun eventFocusMinutes(beginMillis: Long, endMillis: Long, now: Long): Int? {
        if (endMillis <= now) return null
        val minutes = ((endMillis - maxOf(beginMillis, now)) / 60_000L).toInt()
        return minutes.takeIf { it >= MIN_EXACT_SESSION_MINUTES }
    }

    /**
     * An event session of exactly [totalMinutes], on the watch's own timer —
     * the same nuclear rules as any other: no pause, no stop.
     */
    fun startExact(
        totalMinutes: Int,
        subject: String?,
        now: Long,
        sessionId: String = randomId()
    ): WatchTimerState {
        val plan = exactSessionPlan(totalMinutes)
        val state = WatchTimerState(
            active = true,
            running = true,
            phase = WatchPhase.FOCUS,
            focusStartedAt = now,
            durations = WatchDurations(EXACT_FOCUS_MINUTES, EXACT_BREAK_MINUTES, EXACT_BREAK_MINUTES),
            subject = subject,
            targetFocusBlocks = plan.focusBlocks,
            longBreakEvery = Int.MAX_VALUE,
            lastFocusSeconds = plan.lastFocusMinutes * 60,
            sessionId = sessionId
        )
        return state.copy(phaseEndsAt = now + state.focusMillisOfBlock(1))
    }

    fun start(
        durations: WatchDurations,
        subject: String?,
        now: Long,
        session: WatchSession = WatchSession.ONE_HOUR,
        sessionId: String = randomId()
    ): WatchTimerState {
        val threeHours = session == WatchSession.THREE_HOURS
        val used = if (threeHours) THREE_HOUR_DURATIONS else durations
        return WatchTimerState(
            active = true,
            running = true,
            phase = WatchPhase.FOCUS,
            phaseEndsAt = now + used.millisOf(WatchPhase.FOCUS),
            focusStartedAt = now,
            durations = used,
            subject = subject,
            targetFocusBlocks = if (threeHours) THREE_HOUR_BLOCKS
            else focusBlocksForSession(session.minutes, used.focusMinutes, used.shortBreakMinutes),
            longBreakEvery = if (threeHours) THREE_HOUR_LONG_BREAK_EVERY else FOCUS_BLOCKS_PER_LONG_BREAK,
            sessionId = sessionId
        )
    }

    /**
     * How many focus blocks a timed session runs: the whole number whose real
     * length (k focus + (k-1) breaks — a session ends on focus, no trailing
     * break) lands closest to what was asked for.
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

    /**
     * There is no pause (nuclear focus, as on the phone). Resume remains for a
     * run paused by a build from before that rule, which would otherwise be
     * stuck paused forever with no way to stop it either.
     */
    fun resume(state: WatchTimerState, now: Long): WatchTimerState =
        if (!state.active || state.running) state
        else state.copy(running = true, phaseEndsAt = now + state.remainingMillis)

    /**
     * The idle state. Reached only when a session finishes, or an overdue one
     * is closed after a reboot — never by the user, who has no stop.
     */
    fun stop(): WatchTimerState = WatchTimerState()

    /**
     * Ends a break early and starts the next focus block. A focus block cannot
     * be skipped, and nor can a break of a session the phone started: the phone
     * keeps that session's time, and the two would part.
     */
    fun skipBreak(state: WatchTimerState, now: Long): WatchTimerState =
        if (!state.active || state.phase == WatchPhase.FOCUS || state.following) state
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
        val done = state.completedFocus + 1
        val block = FocusBlock(
            // A state stored before sessions had ids keeps a random one.
            id = if (state.sessionId.isBlank()) newId() else blockId(state.sessionId, done),
            startedAt = state.focusStartedAt,
            endedAt = endedAt,
            minutes = (state.focusMillisOfBlock(done) / 60_000L).toInt(),
            epochDay = epochDayOf(endedAt, zone),
            subject = state.subject
        )
        // A timed session ends on its last focus block; its trailing break is
        // never run, as on the phone.
        if (state.targetFocusBlocks in 1..done) {
            return Advance(stop(), block, sessionFinished = true)
        }
        val next = if (done % state.longBreakEvery.coerceAtLeast(1) == 0) WatchPhase.LONG_BREAK
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
        phaseEndsAt = now + state.focusMillisOfBlock(state.completedFocus + 1),
        remainingMillis = 0L,
        focusStartedAt = now
    )

    private fun randomId(): String = UUID.randomUUID().toString()
}
