package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.sync.SyncWorker
import com.ogenrwotdaniel.wakeproof.widget.WakeProofWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Turns focus blocks the watch left on the Data Layer into VERIFIED FOCUS logs,
 * then pushes stats, whose `KEY_SESSION_ACKED` id list is the acknowledgement
 * the watch waits for. The local delete only tidies this device's copy — it
 * does NOT reach the watch, since only the writing node can delete an item.
 *
 * Two ways in, and both are needed:
 * - [importItems] from `PhoneCommandListenerService.onDataChanged`, the fast path.
 * - [sweep], which reads every session item still on the layer. A DATA_CHANGED
 *   event is delivered ONCE: if it is missed — the app being reinstalled, the
 *   process frozen by the OEM, an insert that threw — the item has already
 *   synced and no reconnection will raise another event for it. Without the
 *   sweep such a block sits on the watch as "waiting for phone" forever.
 *   Found on 2026-10-15 with three blocks stranded after two reinstalls.
 */
@Singleton
class WatchBlockImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val completionRepository: CompletionRepository,
    private val wearStatsSync: WearStatsSync
) {

    /**
     * The listener and a sweep can see the same item at once; `importFocusBlock`
     * is a check-then-insert, so they take turns.
     */
    private val mutex = Mutex()

    /** Imports [items] (session URI → its DataMap); returns how many were new. */
    suspend fun importItems(items: List<Pair<Uri, DataMap>>): Int {
        if (items.isEmpty()) return 0
        val imported = mutex.withLock {
            val today = LocalDate.now().toEpochDay()
            val dataClient = Wearable.getDataClient(context)
            var imported = 0
            for ((uri, map) in items) {
                val block = WatchSessionImport.accept(
                    id = map.getString(WearContract.KEY_SESSION_ID),
                    epochDay = map.getLong(WearContract.KEY_SESSION_EPOCH_DAY),
                    minutes = map.getInt(WearContract.KEY_SESSION_MINUTES),
                    subject = map.getString(WearContract.KEY_SESSION_SUBJECT),
                    todayEpochDay = today
                )
                val recorded = block == null || runCatching {
                    if (completionRepository.importFocusBlock(
                            block.id, block.epochDay, block.minutes, block.subject
                        )
                    ) imported++
                }.isSuccess
                // A rejected item is acknowledged too — redelivering it forever
                // would not make it valid — but a failed insert is not: the next
                // sweep retries it.
                if (recorded) runCatching { dataClient.deleteDataItems(uri).await() }
            }
            imported
        }
        // Push even when nothing was new: a block seen again is one the watch is
        // still holding, and only a stats push carrying its id releases it.
        runCatching { wearStatsSync.push() }
        if (imported > 0) {
            runCatching { WakeProofWidget().updateAll(context) }
            SyncWorker.requestOneOff(context)
        }
        return imported
    }

    /**
     * Imports every watch block still on the Data Layer. Cheap — a local read of
     * this device's copy of the layer, no Bluetooth round trip — and a silent
     * no-op on a device with no watch (the tablet) or no Wear API.
     */
    suspend fun sweep(): Int {
        val items = runCatching {
            val buffer = Wearable.getDataClient(context).dataItems.await()
            try {
                buffer.filter { it.uri.path?.startsWith(WearContract.SESSION_PATH_PREFIX) == true }
                    .map { it.uri to DataMapItem.fromDataItem(it).dataMap }
            } finally {
                buffer.release()
            }
        }.onFailure { Log.d(TAG, "Session sweep skipped: ${it.message}") }.getOrDefault(emptyList())
        val imported = importItems(items)
        if (items.isNotEmpty()) Log.i(TAG, "Sweep found ${items.size} watch block(s), $imported new")
        return imported
    }

    private companion object {
        const val TAG = "WatchBlockImporter"
    }
}
