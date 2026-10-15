package com.ogenrwotdaniel.wakeproof.wear

/**
 * The phase-change buzzes, as vibration waveforms: even indices are gaps, odd
 * ones are pulses, and every pulse is played at full motor strength.
 *
 * Long on purpose (2026-10-15, after a week on the wrist): a three-pulse buzz
 * was easy to miss, and a missed "back to focus" turns a five-minute break into
 * an hour. The buzz runs until it ends or the user acknowledges it — tapping
 * the screen, opening the app, "Got it" on the alert, or swiping it away. See
 * [Buzzer].
 *
 * Told apart by feel as well as length: back to focus is long pulses with
 * short gaps, close to continuous; into a break is a looser, quicker rhythm.
 */
object BuzzPattern {

    /** Back to focus: the one that must not be slept through. ~30 s. */
    val INTO_FOCUS = pulses(totalMillis = 30_000, onMillis = 1_000, offMillis = 250)

    /** Into a break. ~10 s. */
    val INTO_BREAK = pulses(totalMillis = 10_000, onMillis = 600, offMillis = 400)

    /** A timed session's last block ended. ~10 s of slow, heavy pulses. */
    val SESSION_DONE = pulses(totalMillis = 10_000, onMillis = 1_500, offMillis = 500)

    /** Confirms a tap the user just made (Skip break): no need to announce it. */
    val TAP = longArrayOf(0, 300, 150, 300)

    /**
     * Repeats [onMillis] on, [offMillis] off, for as many whole pulses as fit in
     * [totalMillis]. Ends on a pulse, never on a gap: a trailing gap is just a
     * vibration that looks longer than it feels.
     */
    fun pulses(totalMillis: Long, onMillis: Long, offMillis: Long): LongArray {
        require(onMillis > 0 && offMillis >= 0 && totalMillis >= onMillis)
        val timings = mutableListOf(0L)
        var elapsed = 0L
        while (elapsed + onMillis <= totalMillis) {
            if (elapsed > 0) timings += offMillis
            timings += onMillis
            elapsed += onMillis + offMillis
        }
        return timings.toLongArray()
    }

    /** How long a waveform plays, gaps included. */
    fun lengthOf(pattern: LongArray): Long = pattern.sum()
}

/** Today's focus on this watch's best knowledge, against the daily goal (0 = none set). */
data class FocusDay(val minutes: Int, val goal: Int)

/**
 * True when [added] minutes carried today across the goal — exactly once a
 * day, since the next block starts at or past it. No goal, no crossing.
 */
fun crossedGoal(before: Int, added: Int, goal: Int): Boolean =
    goal > 0 && before < goal && before + added >= goal

/** "4 blocks · 1h 40m focused" plus, when known, "Today 3h 20m of 6h". */
fun sessionSummary(blocks: Int, focusMinutes: Int, day: FocusDay?): String = buildString {
    append("$blocks block${if (blocks == 1) "" else "s"} · ${formatFocusMinutes(focusMinutes)} focused")
    if (day != null) {
        append("\nToday ${formatFocusMinutes(day.minutes)}")
        if (day.goal > 0) {
            append(" of ${formatFocusMinutes(day.goal)}")
            if (day.minutes >= day.goal) append(" ✓")
        }
    }
}

/** "45m", "2h", "1h 30m". */
fun formatFocusMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
