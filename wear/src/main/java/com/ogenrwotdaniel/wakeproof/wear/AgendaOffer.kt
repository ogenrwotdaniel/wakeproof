package com.ogenrwotdaniel.wakeproof.wear

/**
 * Why an event's title cannot start a focus session on the watch right now.
 *
 * ONE session at a time across devices: a pomodoro running on the phone counts
 * as a session here too. Starting a parallel watch session would credit the
 * same minutes twice (once per device) and wall nothing the phone's session
 * does not already wall.
 */
sealed interface AgendaRefusal {
    /** The watch's own timer runs until [endsAt] ([WatchSessionBeacon.sessionEndsAt]). */
    data class WatchSession(val endsAt: Long) : AgendaRefusal

    /**
     * The phone's timer runs. The phone pushes only the CURRENT phase's finish
     * time (`/wakeproof/timer`), not the session's end, so [blockEndsAt] is when
     * this block or break ends — null when unknown (a paused run, no finish time).
     */
    data class PhoneSession(val blockEndsAt: Long?) : AgendaRefusal

    /** Under [WatchTimer.MIN_EXACT_SESSION_MINUTES] of the event left. */
    data object TooLittleLeft : AgendaRefusal

    /** The event has ended. */
    data object Over : AgendaRefusal

    /** Already ticked off. */
    data object Done : AgendaRefusal
}

/** What tapping an event's title offers: a session of [minutes] ending [endsAt], or a refusal. */
sealed interface AgendaOffer {
    data class Offer(val minutes: Int, val endsAt: Long) : AgendaOffer
    data class Refused(val reason: AgendaRefusal) : AgendaOffer
}

/** Which timer, if any, is a session that blocks starting another. */
sealed interface RunningSession {
    data class Watch(val endsAt: Long) : RunningSession
    data class Phone(val blockEndsAt: Long?) : RunningSession
}

object AgendaOffers {

    /**
     * Pure: the session running on the watch or the phone, or null. The watch's
     * own timer wins (it is the one on the wrist). A paused phone run (older
     * phone builds only) still counts, with no known end. A stale mirror — a
     * phone killed mid-session never says it ended — is no session
     * ([TimerSnapshot.liveAt]); without that the watch refused everything
     * for as long as the dead mirror lingered.
     */
    fun runningSession(watch: WatchTimerState, phone: TimerSnapshot?, now: Long): RunningSession? {
        if (watch.active) return RunningSession.Watch(WatchSessionBeacon.sessionEndsAt(watch, now))
        if (phone == null || !phone.liveAt(now)) return null
        if (!phone.running) return RunningSession.Phone(null)
        // Just past its finish (within the grace): the next push may be in flight.
        return RunningSession.Phone(phone.finishAtMillis.takeIf { it > now })
    }

    /**
     * Pure: what tapping [event]'s title does. Checked in this order — ticked,
     * over, a session already running (watch or phone), too little left — so
     * the reason given is the most basic one that applies.
     */
    fun offer(event: WatchAgendaEvent, watch: WatchTimerState, phone: TimerSnapshot?, now: Long): AgendaOffer {
        if (event.done) return AgendaOffer.Refused(AgendaRefusal.Done)
        if (event.endMillis <= now) return AgendaOffer.Refused(AgendaRefusal.Over)
        when (val running = runningSession(watch, phone, now)) {
            is RunningSession.Watch -> return AgendaOffer.Refused(AgendaRefusal.WatchSession(running.endsAt))
            is RunningSession.Phone -> return AgendaOffer.Refused(AgendaRefusal.PhoneSession(running.blockEndsAt))
            null -> Unit
        }
        val minutes = WatchTimer.eventFocusMinutes(event.beginMillis, event.endMillis, now)
            ?: return AgendaOffer.Refused(AgendaRefusal.TooLittleLeft)
        return AgendaOffer.Offer(minutes, event.endMillis)
    }

    /** Pure: the refusal in words; [time] formats a wall-clock time (12/24 h per the watch). */
    fun refusalText(reason: AgendaRefusal, time: (Long) -> String): String = when (reason) {
        is AgendaRefusal.WatchSession -> "A session is running on this watch until ${time(reason.endsAt)}."
        is AgendaRefusal.PhoneSession -> reason.blockEndsAt
            ?.let { "Your phone is in a focus session. This block ends at ${time(it)}." }
            ?: "Your phone is in a focus session."
        AgendaRefusal.TooLittleLeft -> "Less than ${WatchTimer.MIN_EXACT_SESSION_MINUTES} minutes left."
        AgendaRefusal.Over -> "This event is over."
        AgendaRefusal.Done -> "Already ticked off."
    }

    /** At most this many events on the tile; the rest are one tap away. */
    const val TILE_MAX_EVENTS = 4

    /**
     * Pure: the tile's events — today's not-done ones that have not ended
     * (ongoing or upcoming), earliest first, at most [max].
     */
    fun tileEvents(events: List<WatchAgendaEvent>, now: Long, max: Int = TILE_MAX_EVENTS): List<WatchAgendaEvent> =
        events.asSequence()
            .filter { !it.done && it.endMillis > now }
            .sortedBy { it.beginMillis }
            .take(max)
            .toList()

    const val TILE_MAX_FRESHNESS_MILLIS = 15 * 60_000L
    const val TILE_MIN_FRESHNESS_MILLIS = 60_000L

    /**
     * Pure: how long the tile may stay as rendered — until the next moment its
     * content changes by itself (an event starts or ends, the session ends,
     * midnight), capped at [TILE_MAX_FRESHNESS_MILLIS] and floored at
     * [TILE_MIN_FRESHNESS_MILLIS] so a boundary a second away does not spin.
     * A boundary is any instant in [boundaries] after [now].
     */
    fun tileFreshnessMillis(now: Long, boundaries: List<Long>): Long {
        val next = boundaries.filter { it > now }.minOrNull()
            ?: return TILE_MAX_FRESHNESS_MILLIS
        return (next - now).coerceIn(TILE_MIN_FRESHNESS_MILLIS, TILE_MAX_FRESHNESS_MILLIS)
    }
}
