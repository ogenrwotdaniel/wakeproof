package com.ogenrwotdaniel.wakeproof.agenda

import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.pomodoro.MIN_EXACT_SESSION_MINUTES

/**
 * Pure: how many minutes a session for [event] runs if started at [nowMillis],
 * or null when it cannot be focused for — an all-day event, one already over,
 * or under [MIN_EXACT_SESSION_MINUTES] left (2026-11-01). Counted from now,
 * not the event's start: tapped twenty minutes into a lecture, the session
 * ends with the lecture rather than twenty minutes after it.
 */
fun eventFocusMinutes(event: AgendaEvent, nowMillis: Long): Int? {
    if (event.allDay || event.endMillis <= nowMillis) return null
    val from = maxOf(event.beginMillis, nowMillis)
    val minutes = ((event.endMillis - from) / 60_000L).toInt()
    return minutes.takeIf { it >= MIN_EXACT_SESSION_MINUTES }
}

/** How long before a timed event its reminder comes. */
const val EVENT_REMINDER_LEAD_MILLIS = 5 * 60_000L

/**
 * Pure: the next moment a reminder is due — [EVENT_REMINDER_LEAD_MILLIS]
 * before the earliest timed, not-yet-ticked event still ahead — or null.
 * One alarm for the next reminder is all that is ever armed; firing it arms
 * the one after.
 */
fun nextReminderAt(events: List<AgendaEvent>, doneKeys: Set<String>, nowMillis: Long): Long? =
    events.asSequence()
        .filter { !it.allDay && it.key !in doneKeys }
        .map { it.beginMillis - EVENT_REMINDER_LEAD_MILLIS }
        .filter { it > nowMillis }
        .minOrNull()

/**
 * Pure: the events a reminder firing at [nowMillis] is for — timed, not
 * ticked, starting within the lead (a minute's slack either side, since an
 * inexact or late alarm must still catch its event, and two events starting
 * together share one firing).
 */
fun eventsToRemind(events: List<AgendaEvent>, doneKeys: Set<String>, nowMillis: Long): List<AgendaEvent> =
    events.filter {
        !it.allDay && it.key !in doneKeys &&
            it.beginMillis in (nowMillis - 60_000L)..(nowMillis + EVENT_REMINDER_LEAD_MILLIS + 60_000L)
    }

/** "1 h 25 min", "45 min" — the confirm's length, said plainly. */
fun eventFocusLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}
