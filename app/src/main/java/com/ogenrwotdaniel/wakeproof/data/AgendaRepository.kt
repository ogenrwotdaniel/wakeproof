package com.ogenrwotdaniel.wakeproof.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ogenrwotdaniel.wakeproof.wear.WearAgendaSync
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.agendaDataStore: DataStore<Preferences> by preferencesDataStore("agenda")

/**
 * One calendar event instance. [key] (event id + instance start) uniquely
 * identifies a single occurrence, so ticking off one day of a recurring
 * lecture doesn't tick off the whole series.
 */
data class AgendaEvent(
    val key: String,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarName: String
)

/**
 * Reads upcoming events from the device calendar provider — Google Calendar
 * syncs into it, so no network or Google API is involved — and stores which
 * ones the user has ticked off (a local-only concept; the calendar itself has
 * no completion state). Done-keys are pruned to the current window on each
 * write so the set can't grow forever. Every done-key change is re-pushed to
 * the watch ([WearAgendaSync]).
 */
@Singleton
class AgendaRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.agendaDataStore

    fun hasPermission(): Boolean = context.checkSelfPermission(
        Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    /** Instance keys the user has ticked off. */
    val doneKeys: Flow<Set<String>> = dataStore.data.map { it[DONE_KEYS] ?: emptySet() }

    suspend fun setDone(event: AgendaEvent, done: Boolean, validKeys: Set<String>) {
        dataStore.edit { prefs ->
            val current = (prefs[DONE_KEYS] ?: emptySet()).intersect(validKeys)
            prefs[DONE_KEYS] = if (done) current + event.key else current - event.key
        }
        WearAgendaSync.pushSoon(context)
    }

    /**
     * Ticks [key] off without pruning — for the end-of-event check-in, which
     * holds no list of the current window to prune against.
     */
    suspend fun markDone(key: String) {
        dataStore.edit { prefs -> prefs[DONE_KEYS] = (prefs[DONE_KEYS] ?: emptySet()) + key }
        WearAgendaSync.pushSoon(context)
    }

    /**
     * Event instances from the start of today through the next [WINDOW_DAYS] days,
     * every row that could be read; empty when the calendar could not be read.
     */
    suspend fun upcomingEvents(): List<AgendaEvent> = readCalendar()?.events.orEmpty()

    /**
     * One read of the calendar: the rows that could be read, whether that was all
     * of them ([CalendarRead.whole]) and whether it was all of today's
     * ([CalendarRead.todayWhole]). Null without calendar access. A reader that must
     * not mistake a failure for an empty day (Chrome's deadline pass) tells the two
     * apart from one query.
     */
    suspend fun readCalendar(): CalendarRead? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val zone = ZoneId.systemDefault()
        val begin = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().plusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        val events = mutableListOf<AgendaEvent>()
        var badRows = 0
        var badToday = 0
        val read = runCatching {
            val cursor = CalendarContract.Instances.query(context.contentResolver, projection, begin, end)
                ?: error("calendar query returned no cursor")
            cursor.use { rows ->
                while (rows.moveToNext()) {
                    // One odd row is skipped, not the whole read: the agenda, the watch
                    // and Chrome's deadline all lean on the rest.
                    val row = runCatching {
                        val eventId = rows.getLong(0)
                        val instanceBegin = rows.getLong(1)
                        events += AgendaEvent(
                            key = "$eventId:$instanceBegin",
                            title = rows.getString(3).orEmpty().ifBlank { "(untitled)" },
                            beginMillis = instanceBegin,
                            endMillis = rows.getLong(2),
                            allDay = rows.getInt(4) == 1,
                            calendarName = rows.getString(5).orEmpty()
                        )
                    }
                    if (row.isFailure) {
                        badRows++
                        // Its start alone, when that much can be read, tells whether it
                        // could have been one of today's.
                        val startsAt = runCatching { rows.getLong(1) }.getOrNull()
                        // A null start reads back as 0: that is no proof of another day.
                        if (startsAt == null || startsAt <= 0L || mayBeToday(startsAt, begin)) badToday++
                    }
                }
            }
        }
        // A read that broke partway keeps the rows it got, as it always did, but is not whole.
        CalendarRead(
            events.sortedBy { it.beginMillis },
            whole = read.isSuccess && badRows == 0,
            todayWhole = read.isSuccess && badToday == 0
        )
    }

    private companion object {
        val DONE_KEYS = stringSetPreferencesKey("done_event_keys")
        const val WINDOW_DAYS = 7L
    }
}

/**
 * One calendar read; see [AgendaRepository.readCalendar]. [todayWhole]: every row
 * that failed provably starts on another day, so today's rows are all here even
 * when the read is not [whole].
 */
data class CalendarRead(val events: List<AgendaEvent>, val whole: Boolean, val todayWhole: Boolean)

/**
 * Pure: an unreadable row starting at [startMillis] may be one of today's
 * ([todayStartMillis] is today's local midnight). From a day before, since an
 * all-day event starts at UTC midnight, up to 12 hours ahead of the local one,
 * to 25 hours after: the longest local day, which also covers an all-day event's
 * UTC midnight up to 14 hours behind. No wider, or tomorrow's bad row would keep
 * a deleted deadline standing today.
 */
fun mayBeToday(startMillis: Long, todayStartMillis: Long): Boolean =
    startMillis >= todayStartMillis - 86_400_000L && startMillis < todayStartMillis + 25 * 3_600_000L
