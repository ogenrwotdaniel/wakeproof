package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import java.time.LocalDate

/** One day at the wait: visits let through, and waits walked away from. */
data class GateDay(val epochDay: Long, val opened: Int = 0, val walkedAway: Int = 0)

/**
 * A short history of the wait, kept on this device in plain SharedPreferences.
 *
 * Feeds two things: the escalating wait ([gateWaitSeconds] grows with today's
 * [GateDay.opened]) and the week card on the Usage screen, which shows walking
 * away as a score rather than as nothing happening.
 *
 * Stored as `epochDay:opened:walkedAway` entries, [KEEP_DAYS] of them. A day's
 * counts start at zero simply by being a new day — nothing has to run at
 * midnight.
 */
object GateLog {
    private const val PREFS = "gate_log"
    private const val KEY = "days"

    /** Four weeks: enough for the week card and a week-on-week comparison. */
    const val KEEP_DAYS = 28

    fun today(context: Context): GateDay = day(context, LocalDate.now().toEpochDay())

    fun day(context: Context, epochDay: Long): GateDay =
        parseGateLog(read(context))[epochDay] ?: GateDay(epochDay)

    /** The last [count] days, oldest first, zero-filled. */
    fun lastDays(context: Context, count: Int = 7): List<GateDay> {
        val log = parseGateLog(read(context))
        val today = LocalDate.now().toEpochDay()
        return ((count - 1) downTo 0).map { today - it }.map { log[it] ?: GateDay(it) }
    }

    @Synchronized
    fun recordOpened(context: Context) = update(context) { it.copy(opened = it.opened + 1) }

    @Synchronized
    fun recordWalkedAway(context: Context) = update(context) { it.copy(walkedAway = it.walkedAway + 1) }

    private fun update(context: Context, change: (GateDay) -> GateDay) {
        val today = LocalDate.now().toEpochDay()
        val log = parseGateLog(read(context))
        val next = log + (today to change(log[today] ?: GateDay(today)))
        prefs(context).edit().putString(KEY, encodeGateLog(next, today)).apply()
    }

    private fun read(context: Context): String? = prefs(context).getString(KEY, null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Unreadable entries are skipped: a lost count only shortens the wait by one step. */
internal fun parseGateLog(stored: String?): Map<Long, GateDay> =
    stored.orEmpty().split(',').mapNotNull { entry ->
        val parts = entry.split(':')
        if (parts.size != 3) return@mapNotNull null
        val day = parts[0].toLongOrNull() ?: return@mapNotNull null
        val opened = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
        val walked = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
        day to GateDay(day, opened, walked)
    }.toMap()

/** Keeps only the [GateLog.KEEP_DAYS] days up to [today]. */
internal fun encodeGateLog(log: Map<Long, GateDay>, today: Long): String =
    log.values
        .filter { it.epochDay in (today - GateLog.KEEP_DAYS + 1)..today }
        .sortedBy { it.epochDay }
        .joinToString(",") { "${it.epochDay}:${it.opened}:${it.walkedAway}" }
