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
 * tablet through the cloud. MUST match app `WearContract.WATCH_SESSION_*` —
 * kept in step by hand, like the other paths.
 */
internal object WatchSessionContract {
    const val PATH = "/wakeproof/watch_session"
    const val KEY_ENDS_AT = "ws_ends_at"
}

object WatchSessionBeacon {

    private const val TAG = "WatchSessionBeacon"

    /** Publishes [state]'s end. Fire-and-forget: the Data Layer queues it. */
    fun publish(context: Context, state: WatchTimerState, now: Long = System.currentTimeMillis()) {
        val request = PutDataMapRequest.create(WatchSessionContract.PATH).apply {
            dataMap.putLong(WatchSessionContract.KEY_ENDS_AT, sessionEndsAt(state, now))
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
        val focus = state.durations.millisOf(WatchPhase.FOCUS)
        val current = if (state.phase == WatchPhase.FOCUS) state.completedFocus + 1 else state.completedFocus
        if (state.phase == WatchPhase.FOCUS && current < target) end += breakAfter(current)
        for (block in current + 1..target) {
            end += focus
            if (block < target) end += breakAfter(block)
        }
        return end
    }
}
