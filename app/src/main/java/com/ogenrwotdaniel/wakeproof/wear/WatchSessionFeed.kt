package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * When the session running on the paired watch ends (watch wall clock), 0 when
 * none — read off [WearContract.WATCH_SESSION_PATH] over Bluetooth, so the
 * phone walls itself during a watch session with no internet at all
 * (2026-11-01). Emits the stored value first, then every change while
 * collected; a phone with no watch simply emits nothing.
 */
object WatchSessionFeed {

    private const val TAG = "WatchSessionFeed"

    fun endsAt(context: Context): Flow<Long> = callbackFlow {
        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { events ->
            events.filter { it.dataItem.uri.path == WearContract.WATCH_SESSION_PATH }.forEach { event ->
                if (event.type == DataEvent.TYPE_DELETED) trySend(0L)
                else trySend(
                    DataMapItem.fromDataItem(event.dataItem).dataMap
                        .getLong(WearContract.KEY_WATCH_SESSION_ENDS_AT, 0L)
                )
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
                val latest = buffer.maxOfOrNull {
                    DataMapItem.fromDataItem(it).dataMap.getLong(WearContract.KEY_WATCH_SESSION_ENDS_AT, 0L)
                }
                buffer.release()
                latest?.let { trySend(it) }
            }.onFailure { Log.w(TAG, "watch session read failed", it) }
        }
        awaitClose { runCatching { client.removeListener(listener) } }
    }.distinctUntilChanged()
}
