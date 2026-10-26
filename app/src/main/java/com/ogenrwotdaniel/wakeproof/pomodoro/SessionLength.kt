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
 * ([sessionMinutes] <= 0); [sessionPlan] never asks for one.
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

/** The three-hour preset, which runs on fixed lengths rather than the settings. */
const val THREE_HOUR_SESSION_MINUTES = 180

/**
 * The length a start with no length asks for. Free-running sessions are gone:
 * a session cannot be stopped or paused once started (nuclear focus), so one
 * that never ends on its own would be a phone locked until a reboot. The
 * watch's "Start on phone" sends no length and lands here.
 */
const val DEFAULT_SESSION_MINUTES = 60

/** Everything the timer needs to run one session start to finish. */
data class SessionPlan(
    val focusMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val focusBlocks: Int,
    /** A long break follows every this-many focus blocks. */
    val longBreakEvery: Int
)

/**
 * Turns a chosen session length into a plan.
 *
 * The three-hour preset is the user's own recipe and deliberately ignores the
 * length settings: three pomodoros, a 15-minute long break, three more —
 * 6 × 25 min of focus with 5-minute breaks between, 3 h 05 m in all. Built on
 * the settings instead, 50-minute blocks would have made "3 h" nearly six.
 * Every other length runs the user's own blocks with the usual long break
 * every fourth, sized by [focusBlocksForSession].
 */
fun sessionPlan(
    sessionMinutes: Int,
    focusMinutes: Int,
    shortBreakMinutes: Int,
    longBreakMinutes: Int
): SessionPlan {
    if (sessionMinutes == THREE_HOUR_SESSION_MINUTES) {
        return SessionPlan(
            focusMinutes = 25,
            shortBreakMinutes = 5,
            longBreakMinutes = 15,
            focusBlocks = 6,
            longBreakEvery = 3
        )
    }
    val minutes = if (sessionMinutes > 0) sessionMinutes else DEFAULT_SESSION_MINUTES
    return SessionPlan(
        focusMinutes = focusMinutes,
        shortBreakMinutes = shortBreakMinutes,
        longBreakMinutes = longBreakMinutes,
        focusBlocks = focusBlocksForSession(minutes, focusMinutes, shortBreakMinutes),
        longBreakEvery = 4
    )
}
