package com.ogenrwotdaniel.wakeproof.pomodoro

/**
 * Whole seconds left until [deadlineMillis], rounded UP — a countdown shows
 * "0:01" for the whole of its last second and reaches 0 only at the deadline.
 * Both clocks are the same monotonic source. Never negative.
 */
fun remainingSecondsAt(deadlineMillis: Long, nowMillis: Long): Int {
    val msLeft = deadlineMillis - nowMillis
    if (msLeft <= 0) return 0
    return ((msLeft + 999) / 1_000).toInt()
}
