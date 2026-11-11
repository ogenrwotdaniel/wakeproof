package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * When the session running on the paired watch ends (watch wall clock), 0 when
 * none — read off [WearContract.WATCH_SESSION_PATH] over Bluetooth, so the
 * phone walls itself during a watch session with no internet at all
 * (2026-11-01). Emits the stored value first, then every change while
 * collected; a phone with no watch simply emits nothing. Since 2026-11-05
 * the same item also carries the live timer ([timer]).
 */
object WatchSessionFeed {

    private const val TAG = "WatchSessionFeed"

    fun endsAt(context: Context): Flow<Long> = items(context)
        .map { it?.getLong(WearContract.KEY_WATCH_SESSION_ENDS_AT, 0L) ?: 0L }
        .distinctUntilChanged()

    /**
     * The watch's live timer off the same item (2026-11-05), so the phone can
     * SHOW a watch session with no internet — before this it learned of one only
     * through the cloud, and an offline phone said nothing. Null when the watch
     * is idle, or runs a build that sends only the end time.
     */
    fun timer(context: Context): Flow<LiveWatchTimer?> = items(context)
        .map { it?.let(::parseTimer) }
        .distinctUntilChanged()

    /** Pure: a [LiveWatchTimer] from the item's map, null for an older watch's item. */
    internal fun parseTimer(map: DataMap): LiveWatchTimer? {
        if (!map.containsKey(WearContract.KEY_WS_ACTIVE)) return null
        return LiveWatchTimer(
            active = map.getBoolean(WearContract.KEY_WS_ACTIVE, false),
            running = map.getBoolean(WearContract.KEY_WS_RUNNING, false),
            phase = map.getString(WearContract.KEY_WS_PHASE) ?: "FOCUS",
            phaseEndsAt = map.getLong(WearContract.KEY_WS_PHASE_ENDS_AT, 0L),
            remainingMillis = map.getLong(WearContract.KEY_WS_REMAINING, 0L),
            completedFocus = map.getInt(WearContract.KEY_WS_COMPLETED, 0),
            targetFocusBlocks = map.getInt(WearContract.KEY_WS_TARGET, 0),
            subject = map.getString(WearContract.KEY_WS_SUBJECT)?.takeIf { it.isNotBlank() },
            serverUpdatedAtMillis = map.getLong(WearContract.KEY_WS_UPDATED_AT, 0L).takeIf { it > 0L },
            sessionId = map.getString(WearContract.KEY_WS_SESSION_ID).orEmpty(),
            origin = map.getString(WearContract.KEY_WS_ORIGIN)?.takeIf { it.isNotBlank() }
                ?: com.ogenrwotdaniel.wakeproof.pomodoro.SharedSession.ORIGIN_WATCH,
            focusMinutes = map.getInt(WearContract.KEY_WS_FOCUS_MIN, 0),
            shortBreakMinutes = map.getInt(WearContract.KEY_WS_SHORT_MIN, 0),
            longBreakMinutes = map.getInt(WearContract.KEY_WS_LONG_MIN, 0),
            longBreakEvery = map.getInt(WearContract.KEY_WS_LONG_EVERY, 0),
            lastFocusSeconds = map.getInt(WearContract.KEY_WS_LAST_FOCUS_SEC, 0)
        )
    }

    /** The item's map: the stored one first, then every change; null once deleted. */
    private fun items(context: Context): Flow<DataMap?> = callbackFlow {
        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { events ->
            events.filter { it.dataItem.uri.path == WearContract.WATCH_SESSION_PATH }.forEach { event ->
                if (event.type == DataEvent.TYPE_DELETED) trySend(null)
                else trySend(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        }
        runCatching { client.addListener(listener) }
            .onFailure { Log.w(TAG, "no Data Layer", it) }
        launch {
            runCatching {
                val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME)
                    .path(WearContract.WATCH_SESSION_PATH).build()
                val buffer = client.getDataItems(uri).await()
                // One item per writing node; a phone has one watch in practice.
                val latest = buffer.map { DataMapItem.fromDataItem(it).dataMap }
                    .maxByOrNull { it.getLong(WearContract.KEY_WATCH_SESSION_ENDS_AT, 0L) }
                buffer.release()
                latest?.let { trySend(it) }
            }.onFailure { Log.w(TAG, "watch session read failed", it) }
        }
        awaitClose { runCatching { client.removeListener(listener) } }
    }
}
