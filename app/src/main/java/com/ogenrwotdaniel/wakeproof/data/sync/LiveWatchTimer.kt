package com.ogenrwotdaniel.wakeproof.data.sync

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
    /** Server time of the last write, or null while that is unknown. */
    val serverUpdatedAtMillis: Long?
) {
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
                serverUpdatedAtMillis = serverUpdatedAtMillis
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

    private val BREAKS = setOf("SHORT_BREAK", "LONG_BREAK")
}
