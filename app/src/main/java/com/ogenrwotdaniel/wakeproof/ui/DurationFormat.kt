package com.ogenrwotdaniel.wakeproof.ui

/**
 * A whole number of minutes written the way a goal is spoken: "45 min", "2h",
 * "7h 30m".
 *
 * Exists because the daily focus goal now runs to twelve hours, and "720 min"
 * is a number nobody converts in their head while dragging a slider.
 */
fun formatDurationMinutes(minutes: Int): String {
    val total = minutes.coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}
