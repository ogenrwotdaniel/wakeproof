package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
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
     * items still on the Data Layer, plus anything stuck in the local copy.
     */
    suspend fun pending(context: Context): List<FocusBlock> = withContext(Dispatchers.IO) {
        val onLayer = runCatching {
            val buffer = Wearable.getDataClient(context).dataItems.await()
            try {
                buffer.filter { it.uri.path?.startsWith(SessionContract.PATH_PREFIX) == true }
                    .mapNotNull { item ->
                        val map = DataMapItem.fromDataItem(item).dataMap
                        FocusBlock(
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
        val local = prefs(context).getStringSet(KEY_UNSENT, emptySet()).orEmpty().mapNotNull(::decode)
        (onLayer + local).distinctBy { it.id }
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
    private fun encode(b: FocusBlock): String =
        listOf(b.id, b.startedAt, b.endedAt, b.minutes, b.epochDay, b.subject.orEmpty())
            .joinToString(SEP)

    private fun decode(raw: String): FocusBlock? {
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
