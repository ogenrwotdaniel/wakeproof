package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * `/wakeproof/agenda` — today's timed calendar events, pushed by the phone's
 * `WearAgendaSync`. A hand-kept COPY of the `AGENDA_*` / `KEY_AGENDA_*`
 * constants in the phone's `WearContract`, like the other Data Layer paths:
 * keep the two in step.
 */
internal object AgendaContract {
    const val PATH = "/wakeproof/agenda"
    const val KEY_DAY = "ag_day"
    const val KEY_ALLOWED = "ag_allowed"
    const val KEY_KEYS = "ag_keys"
    const val KEY_TITLES = "ag_titles"
    const val KEY_BEGINS = "ag_begins"
    const val KEY_ENDS = "ag_ends"
    const val KEY_DONE = "ag_done"
}

/** One of today's events as the watch lists it. [key] is the phone's instance key. */
data class WatchAgendaEvent(
    val key: String,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val done: Boolean
)

/**
 * What the watch knows of today's calendar. [present] is false before the
 * phone ever pushed; [epochDay] is the phone's day when it pushed, and a list
 * from another day is not today's ([forDay]).
 */
data class WatchAgendaList(
    val present: Boolean = false,
    val epochDay: Long = 0L,
    val allowed: Boolean = true,
    val events: List<WatchAgendaEvent> = emptyList()
) {
    /** Today's events, or none when the push is from another day. */
    fun forDay(today: Long): List<WatchAgendaEvent> = if (epochDay == today) events else emptyList()
}

object WatchAgenda {

    /**
     * Pure: the parallel arrays of a push back into events. Arrays of
     * different lengths (a phone build that added a field) are read to the
     * shortest, never past it; a blank key is dropped, since a tick for it
     * could not name anything.
     */
    fun parse(
        epochDay: Long,
        allowed: Boolean,
        keys: List<String>?,
        titles: List<String>?,
        begins: LongArray?,
        ends: LongArray?,
        done: List<String>?
    ): WatchAgendaList {
        val k = keys.orEmpty()
        val t = titles.orEmpty()
        val b = begins ?: LongArray(0)
        val e = ends ?: LongArray(0)
        val doneSet = done.orEmpty().toSet()
        val n = minOf(k.size, t.size, b.size, e.size)
        val events = (0 until n)
            .filter { k[it].isNotBlank() }
            .map { WatchAgendaEvent(k[it], t[it], b[it], e[it], k[it] in doneSet) }
            .sortedBy { it.beginMillis }
        return WatchAgendaList(present = true, epochDay = epochDay, allowed = allowed, events = events)
    }

    fun from(map: DataMap): WatchAgendaList = parse(
        epochDay = map.getLong(AgendaContract.KEY_DAY, 0L),
        allowed = map.getBoolean(AgendaContract.KEY_ALLOWED, true),
        keys = map.getStringArrayList(AgendaContract.KEY_KEYS),
        titles = map.getStringArrayList(AgendaContract.KEY_TITLES),
        begins = map.getLongArray(AgendaContract.KEY_BEGINS),
        ends = map.getLongArray(AgendaContract.KEY_ENDS),
        done = map.getStringArrayList(AgendaContract.KEY_DONE)
    )

    /** The phone's last push, or an empty, not-[WatchAgendaList.present] list. */
    suspend fun read(context: Context): WatchAgendaList = withContext(Dispatchers.IO) {
        val buffer = runCatching {
            Wearable.getDataClient(context).dataItems.await()
        }.getOrNull() ?: return@withContext WatchAgendaList()
        try {
            val item = buffer.firstOrNull { it.uri.path == AgendaContract.PATH }
                ?: return@withContext WatchAgendaList()
            from(DataMapItem.fromDataItem(item).dataMap)
        } finally {
            buffer.release()
        }
    }

    /** "1 h 25 m", "1 h", "45 min" — the confirm's length. */
    fun lengthLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m m"
        }
    }
}
