package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Session contract — MUST match app `WearContract.SESSION_*` (kept in sync by hand,
 * like the other three paths).
 */
internal object SessionContract {
    /** One DataItem per block: `/wakeproof/session/<block id>`. */
    const val PATH_PREFIX = "/wakeproof/session/"
    const val KEY_ID = "s_id"
    const val KEY_STARTED = "s_started"
    const val KEY_ENDED = "s_ended"
    const val KEY_MINUTES = "s_minutes"
    const val KEY_EPOCH_DAY = "s_epoch_day"
    const val KEY_SUBJECT = "s_subject"

    /**
     * String list on the phone's STATS item: block ids the phone holds. This is
     * the acknowledgement — the watch deletes its own items once listed. (A
     * delete from the phone only removes the phone's copy: an item belongs to
     * the node that wrote it.) MUST match app `WearContract.KEY_SESSION_ACKED`.
     */
    const val KEY_ACKED = "s_acked"
}

/**
 * Carries focus blocks finished on the watch to the phone, whenever the phone
 * is next reachable — minutes later over Bluetooth, or days later.
 *
 * ### The Data Layer IS the queue
 *
 * Each block is written as its own DataItem. A DataItem is stored on the watch
 * first and replicated when a connection exists (Bluetooth, or Wi-Fi/LTE via
 * the cloud node), so nothing here has to watch the connection or retry. The
 * phone imports the block and then DELETES the item; that deletion syncing back
 * is the acknowledgement, and it is also why "waiting to sync" can be read
 * straight off the local DataItems without a second bookkeeping store.
 *
 * DataClient, not MessageClient, for the opposite reason to the timer
 * commands: a message to an absent phone is simply lost, and a block that
 * never arrives is a day's focus the streak never sees.
 *
 * ### The one local copy
 *
 * The block goes into SharedPreferences BEFORE the put and leaves only once the
 * put succeeds. A put can fail (Play services restarting, an update in
 * progress), and the alarm receiver that produced the block will not get a
 * second chance at it. [flush] retries anything stranded there.
 */
internal object FocusSessionOutbox {

    private const val TAG = "FocusSessionOutbox"
    private const val FILE = "focus_outbox"
    private const val KEY_UNSENT = "unsent"
    private const val SEP = "|"

    suspend fun enqueue(context: Context, block: FocusBlock) {
        val prefs = prefs(context)
        val pending = prefs.getStringSet(KEY_UNSENT, emptySet()).orEmpty() + encode(block)
        prefs.edit(commit = true) { putStringSet(KEY_UNSENT, pending) }
        flush(context)
    }

    /** Re-puts anything that was recorded but never reached the Data Layer. */
    suspend fun flush(context: Context) {
        val prefs = prefs(context)
        val unsent = prefs.getStringSet(KEY_UNSENT, emptySet()).orEmpty()
        if (unsent.isEmpty()) return
        val delivered = unsent.filter { raw ->
            val block = decode(raw) ?: return@filter true // unreadable: drop rather than retry forever
            put(context, block)
        }.toSet()
        if (delivered.isEmpty()) return
        // Re-read: another caller may have enqueued while this one was putting.
        val now = prefs.getStringSet(KEY_UNSENT, emptySet()).orEmpty()
        prefs.edit(commit = true) { putStringSet(KEY_UNSENT, now - delivered) }
    }

    /**
     * Blocks that have not been acknowledged by the phone yet: the session
     * items still on the Data Layer, plus anything stuck in the local copy,
     * minus every id the phone's latest stats push lists as received.
     *
     * Acknowledged items are deleted here as a side effect. This node wrote
     * them, so this is the one delete that actually removes them everywhere.
     */
    suspend fun pending(context: Context): List<FocusBlock> = withContext(Dispatchers.IO) {
        val dataClient = Wearable.getDataClient(context)
        var acked: Set<String> = emptySet()
        val onLayer = runCatching {
            val buffer = dataClient.dataItems.await()
            try {
                acked = buffer.firstOrNull { it.uri.path == WearStatsPath.STATS }
                    ?.let { DataMapItem.fromDataItem(it).dataMap.getStringArrayList(SessionContract.KEY_ACKED) }
                    .orEmpty().toSet()
                buffer.filter { it.uri.path?.startsWith(SessionContract.PATH_PREFIX) == true }
                    .mapNotNull { item ->
                        val map = DataMapItem.fromDataItem(item).dataMap
                        item.uri to FocusBlock(
                            id = map.getString(SessionContract.KEY_ID) ?: return@mapNotNull null,
                            startedAt = map.getLong(SessionContract.KEY_STARTED),
                            endedAt = map.getLong(SessionContract.KEY_ENDED),
                            minutes = map.getInt(SessionContract.KEY_MINUTES),
                            epochDay = map.getLong(SessionContract.KEY_EPOCH_DAY),
                            subject = map.getString(SessionContract.KEY_SUBJECT)
                        )
                    }
            } finally {
                buffer.release()
            }
        }.getOrDefault(emptyList())

        onLayer.filter { (_, block) -> block.id in acked }.forEach { (uri, block) ->
            runCatching { dataClient.deleteDataItems(uri).await() }
                .onSuccess { Log.d(TAG, "Phone has ${block.id}; removed from the layer") }
        }
        val prefs = prefs(context)
        val unsent = prefs.getStringSet(KEY_UNSENT, emptySet()).orEmpty()
        val stillUnsent = unsent.filterNot { raw -> decode(raw)?.id in acked }.toSet()
        if (stillUnsent.size != unsent.size) prefs.edit(commit = true) { putStringSet(KEY_UNSENT, stillUnsent) }

        val waiting = onLayer.map { it.second }.filterNot { it.id in acked } + stillUnsent.mapNotNull(::decode)
        waiting.distinctBy { it.id }.also { all ->
            if (all.isNotEmpty()) {
                Log.d(TAG, "Waiting for phone: " + all.joinToString { "${it.id} day=${it.epochDay} ${it.minutes}m" })
            }
        }
    }

    private suspend fun put(context: Context, block: FocusBlock): Boolean {
        val request = PutDataMapRequest.create(SessionContract.PATH_PREFIX + block.id).apply {
            dataMap.putString(SessionContract.KEY_ID, block.id)
            dataMap.putLong(SessionContract.KEY_STARTED, block.startedAt)
            dataMap.putLong(SessionContract.KEY_ENDED, block.endedAt)
            dataMap.putInt(SessionContract.KEY_MINUTES, block.minutes)
            dataMap.putLong(SessionContract.KEY_EPOCH_DAY, block.epochDay)
            block.subject?.let { dataMap.putString(SessionContract.KEY_SUBJECT, it) }
        }.asPutDataRequest().setUrgent()
        return runCatching { Wearable.getDataClient(context).putDataItem(request).await() }.isSuccess
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Subject last, so a "|" typed into a subject name cannot shift the other fields. */
    internal fun encode(b: FocusBlock): String =
        listOf(b.id, b.startedAt, b.endedAt, b.minutes, b.epochDay, b.subject.orEmpty())
            .joinToString(SEP)

    internal fun decode(raw: String): FocusBlock? {
        val f = raw.split(SEP, limit = 6)
        if (f.size != 6) return null
        return FocusBlock(
            id = f[0],
            startedAt = f[1].toLongOrNull() ?: return null,
            endedAt = f[2].toLongOrNull() ?: return null,
            minutes = f[3].toIntOrNull() ?: return null,
            epochDay = f[4].toLongOrNull() ?: return null,
            subject = f[5].ifEmpty { null }
        )
    }
}
