package com.ogenrwotdaniel.wakeproof.data

/** The screen turning on ([interactive]) or off at [at] (epoch millis). */
data class ScreenEvent(val at: Long, val interactive: Boolean)

/**
 * One night's screen use inside the sleep wall. [epochDay] is the evening's
 * day; [minutes] is null when the night could not be measured (no usage
 * access, or the system has already dropped that night's events).
 */
data class NightScreen(val epochDay: Long, val minutes: Int?)

/** Screen time inside the night a phone may show and still count as down on time. */
const val SLEEP_GRACE_MINUTES = 5

/**
 * Pure: how long the screen was on between [start] and [end], from [events]
 * in time order reaching back before [start]. Null when there is no event at
 * all to go on.
 *
 * The state at [start] is the last event before it. With none, it is the
 * opposite of the first one inside: a screen that turns off must have been on.
 */
fun screenOnMillis(events: List<ScreenEvent>, start: Long, end: Long): Long? {
    if (events.isEmpty() || end <= start) return null
    val before = events.lastOrNull { it.at <= start }
    val inside = events.filter { it.at > start && it.at < end }
    var on = before?.interactive ?: inside.firstOrNull()?.let { !it.interactive } ?: return null
    var since = start
    var total = 0L
    for (event in inside) {
        if (on) total += event.at - since
        on = event.interactive
        since = event.at
    }
    if (on) total += end - since
    return total
}

/** Pure: the night counts as phone down on time. */
fun nightOnTime(night: NightScreen): Boolean =
    night.minutes != null && night.minutes <= SLEEP_GRACE_MINUTES

/** How many of the measured nights the phone was down on time. */
data class SleepScore(val onTime: Int, val measured: Int) {
    val percent: Int get() = if (measured == 0) 0 else onTime * 100 / measured
}

/** Pure: the score over [nights]; unmeasured nights count neither way. */
fun sleepScore(nights: List<NightScreen>): SleepScore {
    val measured = nights.filter { it.minutes != null }
    return SleepScore(onTime = measured.count(::nightOnTime), measured = measured.size)
}
