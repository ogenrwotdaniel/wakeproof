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
 * write so the set can't grow forever.
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
    }

    /** Event instances from the start of today through the next [WINDOW_DAYS] days. */
    suspend fun upcomingEvents(): List<AgendaEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
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
        runCatching {
            CalendarContract.Instances.query(context.contentResolver, projection, begin, end)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val eventId = cursor.getLong(0)
                        val instanceBegin = cursor.getLong(1)
                        events += AgendaEvent(
                            key = "$eventId:$instanceBegin",
                            title = cursor.getString(3).orEmpty().ifBlank { "(untitled)" },
                            beginMillis = instanceBegin,
                            endMillis = cursor.getLong(2),
                            allDay = cursor.getInt(4) == 1,
                            calendarName = cursor.getString(5).orEmpty()
                        )
                    }
                }
        }
        events.sortedBy { it.beginMillis }
    }

    private companion object {
        val DONE_KEYS = stringSetPreferencesKey("done_event_keys")
        const val WINDOW_DAYS = 7L
    }
}
