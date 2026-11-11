package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Tells the phone how long a session on the watch walls it (2026-11-01). A
 * session started on the phone walled the tablet and the tablet's walled the
 * phone, but one started on the watch walled nothing — the phone in the other
 * hand stayed wide open.
 *
 * One DataItem, `/wakeproof/watch_session`, holding when the session ends
 * (watch wall clock), 0 once it is over. A DataItem rather than a message
 * because this is state: the phone reads the latest whenever it next connects,
 * and over Bluetooth, so it needs no internet. The phone passes it on to the
 * tablet through the cloud. Since 2026-11-05 it also carries the live timer,
 * so the phone shows "Focus on your watch" with no internet. MUST match app `WearContract.WATCH_SESSION_*` —
 * kept in step by hand, like the other paths.
 */
internal object WatchSessionContract {
    const val PATH = "/wakeproof/watch_session"
    const val KEY_ENDS_AT = "ws_ends_at"

    // The live timer itself (2026-11-05), so the phone can SHOW the session,
    // not only wall itself — over Bluetooth, with the phone offline. Same
    // meaning as the cloud's `live/watchTimer` fields.
    const val KEY_ACTIVE = "ws_active"
    const val KEY_RUNNING = "ws_running"
    const val KEY_PHASE = "ws_phase"
    const val KEY_PHASE_ENDS_AT = "ws_phase_ends_at"
    const val KEY_REMAINING = "ws_remaining"
    const val KEY_COMPLETED = "ws_completed"
    const val KEY_TARGET = "ws_target"
    const val KEY_SUBJECT = "ws_subject"
    const val KEY_UPDATED_AT = "ws_updated_at"

    // The rest of the session (2026-11-11), so the phone can run it too
    // ("Continue here"): app `WearContract.KEY_WS_*`, read into `SharedSession`.
    const val KEY_SESSION_ID = "ws_session_id"
    const val KEY_ORIGIN = "ws_origin"
    const val KEY_FOCUS_MIN = "ws_focus_min"
    const val KEY_SHORT_MIN = "ws_short_min"
    const val KEY_LONG_MIN = "ws_long_min"
    const val KEY_LONG_EVERY = "ws_long_every"
    const val KEY_LAST_FOCUS_SEC = "ws_last_focus_sec"
}

object WatchSessionBeacon {

    private const val TAG = "WatchSessionBeacon"

    /** Publishes [state]'s end. Fire-and-forget: the Data Layer queues it. */
    fun publish(context: Context, state: WatchTimerState, now: Long = System.currentTimeMillis()) {
        val request = PutDataMapRequest.create(WatchSessionContract.PATH).apply {
            dataMap.putLong(WatchSessionContract.KEY_ENDS_AT, sessionEndsAt(state, now))
            dataMap.putBoolean(WatchSessionContract.KEY_ACTIVE, state.active)
            dataMap.putBoolean(WatchSessionContract.KEY_RUNNING, state.running)
            dataMap.putString(WatchSessionContract.KEY_PHASE, state.phase.name)
            dataMap.putLong(WatchSessionContract.KEY_PHASE_ENDS_AT, state.phaseEndsAt)
            dataMap.putLong(WatchSessionContract.KEY_REMAINING, state.remainingMillisAt(now))
            dataMap.putInt(WatchSessionContract.KEY_COMPLETED, state.completedFocus)
            dataMap.putInt(WatchSessionContract.KEY_TARGET, state.targetFocusBlocks)
            dataMap.putString(WatchSessionContract.KEY_SUBJECT, state.subject.orEmpty())
            dataMap.putLong(WatchSessionContract.KEY_UPDATED_AT, now)
            dataMap.putString(WatchSessionContract.KEY_SESSION_ID, state.sessionId)
            dataMap.putString(WatchSessionContract.KEY_ORIGIN, state.origin)
            dataMap.putInt(WatchSessionContract.KEY_FOCUS_MIN, state.durations.focusMinutes)
            dataMap.putInt(WatchSessionContract.KEY_SHORT_MIN, state.durations.shortBreakMinutes)
            dataMap.putInt(WatchSessionContract.KEY_LONG_MIN, state.durations.longBreakMinutes)
            dataMap.putInt(WatchSessionContract.KEY_LONG_EVERY, state.longBreakEvery)
            dataMap.putInt(WatchSessionContract.KEY_LAST_FOCUS_SEC, state.lastFocusSeconds)
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(request) }
            .onFailure { Log.w(TAG, "watch session publish failed", it) }
    }

    /**
     * Pure: when the session in [state] ends, or 0 when none runs. The current
     * phase's end plus every phase still to come, the same sum the phone makes
     * for its own sessions (`sessionRemainingSeconds`). A skipped break only
     * ends it earlier, so the wall can outlast the session by that break —
     * never fall short of it. A run paused by an older build counts from now.
     */
    fun sessionEndsAt(state: WatchTimerState, now: Long): Long {
        if (!state.active) return 0L
        var end = if (state.running) state.phaseEndsAt else now + state.remainingMillis
        val target = state.targetFocusBlocks
        if (target <= 0) return end
        val every = state.longBreakEvery.coerceAtLeast(1)
        fun breakAfter(block: Int) =
            state.durations.millisOf(if (block % every == 0) WatchPhase.LONG_BREAK else WatchPhase.SHORT_BREAK)
        val current = if (state.phase == WatchPhase.FOCUS) state.completedFocus + 1 else state.completedFocus
        if (state.phase == WatchPhase.FOCUS && current < target) end += breakAfter(current)
        for (block in current + 1..target) {
            end += state.focusMillisOfBlock(block)
            if (block < target) end += breakAfter(block)
        }
        return end
    }
}
