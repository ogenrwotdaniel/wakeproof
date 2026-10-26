package com.ogenrwotdaniel.wakeproof.distraction

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * Which app is on screen, folded from activity events as a running state rather
 * than "the last thing that resumed in the past minute".
 *
 * The old answer was a 60 s look-back for the newest ACTIVITY_RESUMED, with the
 * caller holding on to the last non-null result. A phone call broke it: the
 * in-call screen resumes on top of the capped app, and when the call ends the
 * system can drop the user straight back into that app WITHOUT a fresh resume
 * (it was answered from a heads-up, or the call screen shrank to a pill). The
 * newest resume on record was then the in-call screen for good, so the blocker
 * believed the phone app was in front and let the capped app run until the user
 * happened to open something else.
 *
 * Held as a set of resumed activities per package — the same per-ACTIVITY
 * rule as `foldForegroundMillis`, because apps trampoline between activities —
 * plus when each package last resumed. The foreground app is the most recently
 * resumed package that still has something resumed. When the in-call screen
 * pauses, its set empties and the answer falls back to the app underneath,
 * which never paused.
 */
class ResumedActivities {
    private val resumed = mutableMapOf<String, MutableSet<String>>()
    private val lastResumedAt = mutableMapOf<String, Long>()

    fun onResumed(packageName: String, className: String, at: Long) {
        resumed.getOrPut(packageName) { mutableSetOf() } += className
        lastResumedAt[packageName] = at
    }

    /** PAUSED, STOPPED and DESTROYED all mean the same here: no longer in front. */
    fun onLeft(packageName: String, className: String) {
        val set = resumed[packageName] ?: return
        set -= className
        if (set.isEmpty()) resumed -= packageName
    }

    /** The package in front, or null when nothing is known to be resumed. */
    fun foreground(): String? = resumed.keys.maxByOrNull { lastResumedAt[it] ?: 0L }
}

/**
 * Feeds [ResumedActivities] from [UsageStatsManager] incrementally: each call
 * reads only the events since the previous one, so a poll every half second
 * costs a few events, not a walk of the day.
 */
class ForegroundTracker(private val usm: UsageStatsManager) {
    private val state = ResumedActivities()
    private var readUpTo = 0L

    /** The package on screen now; see [ResumedActivities.foreground]. */
    fun current(now: Long = System.currentTimeMillis()): String? {
        // First read seeds from a long look-back, so an app opened well before
        // the service started is still known to be in front.
        val from = if (readUpTo == 0L) now - SEED_LOOKBACK_MILLIS else readUpTo - OVERLAP_MILLIS
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        // Replaying the overlap is harmless: set membership after a replayed
        // run of events is the same as after the run once.
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val className = event.className ?: ""
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    state.onResumed(event.packageName, className, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                ACTIVITY_DESTROYED -> state.onLeft(event.packageName, className)
            }
        }
        readUpTo = now
        return state.foreground()
    }

    private companion object {
        /** `UsageEvents.Event.ACTIVITY_DESTROYED` is a hidden constant (API 29+). */
        const val ACTIVITY_DESTROYED = 24
        const val SEED_LOOKBACK_MILLIS = 6 * 60 * 60 * 1000L

        /**
         * Events are stamped when they happen but can land in the store a moment
         * later; re-reading a short overlap stops one falling between two reads.
         */
        const val OVERLAP_MILLIS = 2_000L
    }
}
