package com.ogenrwotdaniel.wakeproof.wear

/**
 * What the "WakeProof Today" complication shows, in words, for both slot
 * shapes it supports. Pure, so the wording is unit-tested without a watch face.
 *
 * - LONG_TEXT: [longTitle] (optional, the smaller line on most faces) over [longText].
 * - SHORT_TEXT: [shortTitle] over [shortText] (a few characters — a time).
 *
 * [tapEventKey] is the event a tap should open at its confirm in
 * [AgendaActivity]; null opens the plain list.
 */
data class AgendaComplicationContent(
    val longTitle: String?,
    val longText: String,
    val shortTitle: String?,
    val shortText: String,
    val description: String,
    val tapEventKey: String?
)

object AgendaComplicationTexts {

    const val NOTHING_LEFT = "Nothing left today"
    const val NO_AGENDA = "Open WakeProof on phone"
    const val CALENDAR_OFF = "Calendar access is off on phone"
    private const val HEADER = "Today"

    /**
     * Pure: the complication's content at [now]. [session] is
     * [AgendaOffers.runningSession]; [time] formats a wall-clock time (12/24 h
     * per the watch). Events are the tile's own selection
     * ([AgendaOffers.tileEvents]) — today's, not ticked, not yet ended.
     *
     * A running session takes the headline (it is the thing the user is in);
     * the next event stays visible beneath it, since the slot replaces the
     * calendar complication the user relied on to see what comes next.
     */
    fun content(
        agenda: WatchAgendaList,
        today: Long,
        session: RunningSession?,
        now: Long,
        time: (Long) -> String
    ): AgendaComplicationContent {
        val remaining = AgendaOffers.tileEvents(agenda.forDay(today), now, max = Int.MAX_VALUE)
        val next = remaining.firstOrNull()
        fun line(event: WatchAgendaEvent) = "${time(event.beginMillis)} ${event.title}"

        // Why there is nothing to list, when there is nothing.
        val emptyText = when {
            !agenda.present || agenda.epochDay != today -> NO_AGENDA
            !agenda.allowed -> CALENDAR_OFF
            else -> NOTHING_LEFT
        }

        if (session != null) {
            val sessionLong: String
            val shortText: String
            when (session) {
                is RunningSession.Watch -> {
                    sessionLong = "Focus · ends ${time(session.endsAt)}"
                    shortText = time(session.endsAt)
                }
                is RunningSession.Phone -> {
                    sessionLong = "Focus on phone"
                    shortText = session.blockEndsAt?.let(time) ?: "Phone"
                }
            }
            val below = next?.let(::line) ?: emptyText
            return AgendaComplicationContent(
                longTitle = sessionLong,
                longText = below,
                shortTitle = "Focus",
                shortText = shortText,
                description = "WakeProof: $sessionLong. Next: $below",
                tapEventKey = next?.key
            )
        }

        if (next == null) {
            return AgendaComplicationContent(
                longTitle = HEADER,
                longText = emptyText,
                shortTitle = HEADER,
                shortText = if (emptyText == NOTHING_LEFT) "Done" else "—",
                description = "WakeProof Today: $emptyText",
                tapEventKey = null
            )
        }

        val (title, text) = when (remaining.size) {
            1 -> HEADER to line(next)
            2 -> line(next) to line(remaining[1])
            else -> line(next) to "+${remaining.size - 1} more"
        }
        return AgendaComplicationContent(
            longTitle = title,
            longText = text,
            shortTitle = if (next.beginMillis <= now) "Now" else "Next",
            shortText = time(next.beginMillis),
            description = "WakeProof Today: " + remaining.joinToString(", ", transform = ::line),
            tapEventKey = next.key
        )
    }
}
