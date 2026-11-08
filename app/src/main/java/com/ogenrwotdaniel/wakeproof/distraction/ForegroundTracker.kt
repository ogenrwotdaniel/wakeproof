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
    /** When each package last went from nothing resumed to something. */
    private val resumedSince = mutableMapOf<String, Long>()
    /** When each resumed activity last resumed, so a stale one cannot pass for the newest. */
    private val classResumedAt = mutableMapOf<Pair<String, String>, Long>()

    fun onResumed(packageName: String, className: String, at: Long) {
        val pkg = AppGate.effectivePackage(packageName, className)
        if (pkg !in resumed) resumedSince[pkg] = at
        resumed.getOrPut(pkg) { mutableSetOf() } += className
        lastResumedAt[pkg] = at
        classResumedAt[pkg to className] = at
    }

    /** PAUSED, STOPPED and DESTROYED all mean the same here: no longer in front. */
    fun onLeft(packageName: String, className: String) {
        val pkg = AppGate.effectivePackage(packageName, className)
        val set = resumed[pkg] ?: return
        set -= className
        classResumedAt -= pkg to className
        if (set.isEmpty()) resumed -= pkg
    }

    /** The package in front, or null when nothing is known to be resumed. */
    fun foreground(): String? = resumed.keys.maxByOrNull { lastResumedAt[it] ?: 0L }

    /**
     * Whether [packageName] still has an activity resumed. Two packages are
     * resumed at once when one sits in a pop-up window or split screen — which
     * is how a walled app stays usable beside the wall thrown over it.
     */
    fun isResumed(packageName: String): Boolean = packageName in resumed

    /** Whether this exact activity is resumed, for an app that is more than one screen. */
    fun isResumed(packageName: String, className: String): Boolean =
        resumed[packageName]?.contains(className) == true

    /**
     * The activity of [packageName] that resumed last, of those still resumed.
     * An activity whose PAUSED was lost stays in the set, but stops being the
     * newest as soon as the app shows another screen.
     */
    fun newestClass(packageName: String): String? =
        resumed[packageName]?.maxByOrNull { classResumedAt[packageName to it] ?: 0L }

    /** When [packageName] last resumed; 0 when never seen. */
    fun lastResumedAt(packageName: String): Long = lastResumedAt[packageName] ?: 0L

    /**
     * When [packageName]'s current run of resumed activities began; 0 when never
     * seen. Earlier than its last resume when it has two windows up.
     */
    fun resumedSince(packageName: String): Long = resumedSince[packageName] ?: 0L

    /** Drops [packageName] as no longer resumed. */
    fun forget(packageName: String) {
        resumed -= packageName
        classResumedAt.keys.removeAll { it.first == packageName }
    }

    /** Everything resumed now, the app in front included. */
    fun packages(): Set<String> = resumed.keys.toSet()

    /**
     * Forgets everything resumed: a shutdown records no PAUSED for what was on
     * screen, and an entry whose PAUSED was lost would otherwise last for good.
     */
    fun clear() {
        resumed.clear()
        classResumedAt.clear()
        lastResumedAt.clear()
        resumedSince.clear()
    }
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
            fold(state, event)
        }
        readUpTo = now
        return state.foreground()
    }

    /**
     * Folds [packageName]'s events again from its last resume. An event written
     * to the store after the overlap had passed is missed by [current] for good,
     * which leaves an app that has closed looking resumed; a fresh read sees it.
     * Only that app's own span is read, and nothing else in the state is touched.
     *
     * In three steps so the slow read can leave the thread that owns the state:
     * [confirmFrom] and [settle] on it, [resumedIn] on any thread.
     */
    fun confirmFrom(packageName: String): Long? =
        if (state.isResumed(packageName)) state.resumedSince(packageName) - OVERLAP_MILLIS else null

    /** Whether [packageName]'s own events since [from] leave it resumed. Reads the store only. */
    fun resumedIn(packageName: String, from: Long, now: Long = System.currentTimeMillis()): Boolean {
        val fresh = ResumedActivities()
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (AppGate.effectivePackage(event.packageName, event.className ?: "") == packageName) {
                fold(fresh, event)
            }
        }
        return fresh.isResumed(packageName)
    }

    /** As of the last [current] call; see [ResumedActivities.lastResumedAt]. */
    fun lastResumedAt(packageName: String): Long = state.lastResumedAt(packageName)

    /** Applies a [resumedIn] answer: a closed app is forgotten. */
    fun settle(packageName: String, stillResumed: Boolean): Boolean {
        if (!stillResumed) state.forget(packageName)
        return stillResumed
    }

    private fun fold(into: ResumedActivities, event: UsageEvents.Event) {
        val className = event.className ?: ""
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> into.onResumed(event.packageName, className, event.timeStamp)
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED,
            ACTIVITY_DESTROYED -> into.onLeft(event.packageName, className)
            UsageEvents.Event.DEVICE_SHUTDOWN,
            UsageEvents.Event.DEVICE_STARTUP -> into.clear()
        }
    }

    /** As of the last [current] call; see [ResumedActivities.isResumed]. */
    fun isResumed(packageName: String): Boolean = state.isResumed(packageName)

    /** As of the last [current] call; see [ResumedActivities.isResumed]. */
    fun isResumed(packageName: String, className: String): Boolean =
        state.isResumed(packageName, className)

    /** As of the last [current] call; see [ResumedActivities.newestClass]. */
    fun newestClass(packageName: String): String? = state.newestClass(packageName)

    /** For when nothing can be resumed, such as the screen being off. */
    fun forgetResumed() = state.clear()

    /** As of the last [current] call; see [ResumedActivities.packages]. */
    fun resumedPackages(): Set<String> = state.packages()

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
