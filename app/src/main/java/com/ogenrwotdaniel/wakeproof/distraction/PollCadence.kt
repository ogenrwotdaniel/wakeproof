package com.ogenrwotdaniel.wakeproof.distraction

/**
 * How long [AppBlockService] may sleep while a capped app is on screen and its
 * allowance is NOT yet spent.
 *
 * The wall has to land the moment the cap is crossed, which is why the loop
 * polls at 1.5 s. But that rate is only needed *near* the cap. Sitting in an app
 * with 40 minutes of allowance left, the earliest the wall can possibly be
 * needed is 40 minutes away, and checking 1,600 times before then buys nothing.
 *
 * ### Why the ceiling is the baseline-refresh interval, not something larger
 *
 * Usage is extrapolated between measurements: `used = baseline + time since it
 * was taken`, which silently assumes the app stayed in the foreground the whole
 * time. That assumption is already bounded to 30 s by
 * `BASELINE_REFRESH_MILLIS`, so sleeping up to 30 s adds no error that was not
 * there before — it just stops re-asking the same question twenty times per
 * measurement window. Sleeping *past* it would extend the extrapolation itself,
 * which is a correctness change rather than a battery one, so the ceiling stays
 * pinned to it.
 *
 * The floor matters for the opposite reason: [remainingMillis] is computed from
 * an extrapolation that can run fast (the user backgrounded the app and the
 * clock kept counting), so it can arrive negative. Clamping up to the fast poll
 * means the loop degrades to today's behaviour rather than spinning.
 */
fun pollDelayMillis(
    remainingMillis: Long,
    fastMillis: Long = FAST_POLL_MILLIS,
    slowestMillis: Long = MAX_POLL_MILLIS
): Long = remainingMillis.coerceIn(fastMillis, slowestMillis)

/** Mirrors `AppBlockService.POLL_MILLIS`. */
const val FAST_POLL_MILLIS = 1_500L

/** Mirrors `AppBlockService.BASELINE_REFRESH_MILLIS` — see the note above. */
const val MAX_POLL_MILLIS = 30_000L
