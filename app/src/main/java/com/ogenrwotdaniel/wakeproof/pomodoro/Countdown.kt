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

/**
 * Pure: a break has ended, so the full-screen focus timer comes back to the
 * front by itself (2026-11-13, the user: "at the end of every break I have to
 * go back to full screen focus manually"). Only where the full-screen timer is
 * on; never over a ringing alarm or a call, and never over another app
 * ([studying]): in a session that is a study app, Blackboard, the dialer or a
 * permission dialog. It comes back over WakeProof's own screens and its home screen.
 */
fun timerLandsAtFocus(immersiveTimer: Boolean, alarmRinging: Boolean, studying: Boolean): Boolean =
    immersiveTimer && !alarmRinging && !studying
