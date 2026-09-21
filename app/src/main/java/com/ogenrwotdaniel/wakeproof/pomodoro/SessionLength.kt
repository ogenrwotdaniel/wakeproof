package com.ogenrwotdaniel.wakeproof.pomodoro

/**
 * How many focus blocks a timed session of [sessionMinutes] should run.
 *
 * A timed session is a request for a *length*, but the timer can only run whole
 * pomodoros: asking for 90 minutes at 25+5 means three focus blocks with two
 * breaks between them, which is 85 minutes. The job here is to pick the whole
 * number of blocks whose real length lands closest to what was asked for.
 *
 * It used to be `sessionMinutes / cycleMinutes`, which is two mistakes at once:
 *
 * * It counts a trailing break that is never run. A session ends on a focus
 *   block, so *k* blocks take `k * focus + (k - 1) * break`, not `k * cycle`.
 * * It floors. At the default 25+5 that is invisible, because 60, 90 and 120
 *   all divide exactly — but at 50+10 a request for 90 minutes floored to one
 *   50-minute block, missing by 40 minutes, and the user was given barely half
 *   the session they picked.
 *
 * Solving `k * focus + (k - 1) * break = sessionMinutes` for k and rounding
 * gives the nearest whole session. Returns 0 for a free-running session
 * ([sessionMinutes] <= 0), which the timer reads as "loop until stopped".
 */
fun focusBlocksForSession(
    sessionMinutes: Int,
    focusMinutes: Int,
    breakMinutes: Int
): Int {
    if (sessionMinutes <= 0) return 0
    // A zero-length cycle would divide by zero. It should be impossible —
    // the focus-length setting has a floor — but this runs on whatever is in
    // DataStore, and a timed session must not be what crashes the service.
    val cycle = focusMinutes + breakMinutes
    if (cycle <= 0) return 1
    val blocks = (sessionMinutes + breakMinutes + cycle / 2) / cycle
    return blocks.coerceAtLeast(1)
}
