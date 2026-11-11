package com.ogenrwotdaniel.wakeproof.data.sync

import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroPhase
import com.ogenrwotdaniel.wakeproof.pomodoro.SharedSession
import com.ogenrwotdaniel.wakeproof.pomodoro.validSessionId

/**
 * The watch's pomodoro as it last published itself to `users/{uid}/live/watchTimer`
 * — the watch writes it over its own Wi-Fi/LTE on every transition, so this
 * device can show a session the phone has never heard of.
 *
 * Keys MUST match the watch's `CloudContract.T_*` (kept in sync by hand, like
 * the Data Layer paths). Times are the WATCH's wall clock.
 */
data class LiveWatchTimer(
    val active: Boolean,
    val running: Boolean,
    val phase: String,
    val phaseEndsAt: Long,
    val remainingMillis: Long,
    val completedFocus: Int,
    val targetFocusBlocks: Int,
    val subject: String?,
    /**
     * When this copy was written, or null while that is unknown: the server's
     * time for the cloud copy, the watch's own clock for the Bluetooth one.
     */
    val serverUpdatedAtMillis: Long?,
    /** The rest of the session (2026-11-11), so this device can join it; blank or 0 from an older watch. */
    val sessionId: String = "",
    /** Where the session started: the watch, or the phone whose session the watch joined. */
    val origin: String = SharedSession.ORIGIN_WATCH,
    val focusMinutes: Int = 0,
    val shortBreakMinutes: Int = 0,
    val longBreakMinutes: Int = 0,
    val longBreakEvery: Int = 0,
    val lastFocusSeconds: Int = 0
) {
    /** The session to join, or null when idle, paused, or sent by a watch build that cannot share one. */
    fun toShared(): SharedSession? {
        if (!active || !running) return null
        return SharedSession(
            id = validSessionId(sessionId) ?: return null,
            origin = origin,
            phase = PomodoroPhase.entries.firstOrNull { it.name == phase } ?: return null,
            phaseEndsAtMillis = phaseEndsAt,
            completedFocus = completedFocus,
            targetFocusBlocks = targetFocusBlocks,
            focusSeconds = focusMinutes * 60,
            shortBreakSeconds = shortBreakMinutes * 60,
            longBreakSeconds = longBreakMinutes * 60,
            longBreakEvery = longBreakEvery.coerceAtLeast(1),
            lastFocusSeconds = lastFocusSeconds.takeIf { it > 0 } ?: (focusMinutes * 60),
            subject = subject,
            // Every copy of the watch's timer was written by the watch.
            fromOrigin = origin == SharedSession.ORIGIN_WATCH
        )
    }

    companion object {
        /** Null for a document this build cannot read; a missing doc is simply "idle". */
        fun fromFields(fields: Map<String, Any?>, serverUpdatedAtMillis: Long?): LiveWatchTimer? {
            val active = fields["active"] as? Boolean ?: return null
            return LiveWatchTimer(
                active = active,
                running = fields["running"] as? Boolean ?: false,
                phase = fields["phase"] as? String ?: "FOCUS",
                phaseEndsAt = (fields["phaseEndsAt"] as? Number)?.toLong() ?: 0L,
                remainingMillis = (fields["remainingMillis"] as? Number)?.toLong() ?: 0L,
                completedFocus = (fields["completedFocus"] as? Number)?.toInt() ?: 0,
                targetFocusBlocks = (fields["targetFocusBlocks"] as? Number)?.toInt() ?: 0,
                subject = (fields["subject"] as? String)?.takeIf { it.isNotBlank() },
                serverUpdatedAtMillis = serverUpdatedAtMillis,
                sessionId = fields["sessionId"] as? String ?: "",
                origin = (fields["origin"] as? String)?.takeIf { it.isNotBlank() } ?: SharedSession.ORIGIN_WATCH,
                focusMinutes = (fields["focusMinutes"] as? Number)?.toInt() ?: 0,
                shortBreakMinutes = (fields["shortBreakMinutes"] as? Number)?.toInt() ?: 0,
                longBreakMinutes = (fields["longBreakMinutes"] as? Number)?.toInt() ?: 0,
                longBreakEvery = (fields["longBreakEvery"] as? Number)?.toInt() ?: 0,
                lastFocusSeconds = (fields["lastFocusSeconds"] as? Number)?.toInt() ?: 0
            )
        }
    }
}

/** What Home draws for a session running on the watch. */
data class WatchSessionDisplay(
    val phaseLabel: String,
    val remainingSeconds: Long,
    val running: Boolean,
    /** "Block 2 of 4" — the block in progress, as the watch counts it. */
    val blockLabel: String,
    /** The phase is over by the clock but the watch has not said what came next. */
    val awaitingWatch: Boolean
)

/** Pure display rules for [LiveWatchTimer]; unit-tested. */
object LiveWatchTimerRules {

    /**
     * A running phase this far past its end with no word from the watch means
     * the watch went offline (or flat) mid-session. Past that the card is a
     * claim about a session nobody can vouch for, so it goes.
     */
    const val RUNNING_STALE_MILLIS = 10 * 60_000L

    /** A paused session left this long is abandoned in all but name. */
    const val PAUSED_STALE_MILLIS = 12 * 60 * 60_000L

    fun display(timer: LiveWatchTimer?, now: Long): WatchSessionDisplay? {
        if (timer == null || !timer.active) return null
        val label = when (timer.phase) {
            "SHORT_BREAK" -> "Short break"
            "LONG_BREAK" -> "Long break"
            else -> "Focus"
        }
        // In a break the block just finished is the one to name, as on the watch.
        val block = if (timer.phase in BREAKS) timer.completedFocus else timer.completedFocus + 1
        val of = if (timer.targetFocusBlocks > 0) " of ${timer.targetFocusBlocks}" else ""
        val blockLabel = "Block $block$of"

        if (timer.running) {
            val msLeft = timer.phaseEndsAt - now
            if (msLeft < -RUNNING_STALE_MILLIS) return null
            return WatchSessionDisplay(
                phaseLabel = label,
                // Rounded up, like both timers: "0:01" until the phase is really over.
                remainingSeconds = (msLeft.coerceAtLeast(0L) + 999) / 1000,
                running = true,
                blockLabel = blockLabel,
                awaitingWatch = msLeft <= 0L
            )
        }
        val updated = timer.serverUpdatedAtMillis
        if (updated != null && now - updated > PAUSED_STALE_MILLIS) return null
        return WatchSessionDisplay(
            phaseLabel = label,
            remainingSeconds = (timer.remainingMillis.coerceAtLeast(0L) + 999) / 1000,
            running = false,
            blockLabel = blockLabel,
            awaitingWatch = false
        )
    }

    /**
     * Whether [timer] is a session that walls this device: running, and not so
     * far past its phase end that the watch has evidently gone quiet. Breaks
     * count — the wall covers the whole session, as on the phone.
     */
    fun walls(timer: LiveWatchTimer?, now: Long): Boolean =
        timer != null && timer.active && timer.running &&
            timer.phaseEndsAt - now >= -RUNNING_STALE_MILLIS

    /**
     * Two copies of the same watch timer — over Bluetooth and from the cloud —
     * reduced to the one to believe: the newer write. Either path can be the
     * stale one (the phone offline, or the watch out of Bluetooth range but on
     * Wi-Fi), and the newer write is the watch's latest word either way. An
     * undated copy loses to a dated one; a tie goes to Bluetooth, which is local.
     */
    fun newest(bluetooth: LiveWatchTimer?, cloud: LiveWatchTimer?): LiveWatchTimer? {
        if (bluetooth == null) return cloud
        if (cloud == null) return bluetooth
        val b = bluetooth.serverUpdatedAtMillis ?: Long.MIN_VALUE
        val c = cloud.serverUpdatedAtMillis ?: Long.MIN_VALUE
        return if (c > b) cloud else bluetooth
    }

    private val BREAKS = setOf("SHORT_BREAK", "LONG_BREAK")
}
