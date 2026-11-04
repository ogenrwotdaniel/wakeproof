package com.ogenrwotdaniel.wakeproof.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.data.AgendaRepository
import com.ogenrwotdaniel.wakeproof.launcher.eventDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Puts today's timed calendar events on the Data Layer at
 * [WearContract.AGENDA_PATH] (2026-11-04), so the watch can list them and start
 * its OWN timer for one. Same source and same done-keys as the home-screen
 * agenda and the Calendar screen ([AgendaRepository]), so all three agree about
 * what is ticked.
 *
 * State, not a message: a DataItem, so a watch that comes back into range reads
 * the list as it is now. An unchanged list writes the same bytes and raises
 * nothing on the watch, so pushing from every refresh is cheap.
 *
 * Pushed when a done-key changes ([AgendaRepository] calls [pushSoon]), on each
 * launcher / Calendar screen refresh, from the 15-minute and hourly workers,
 * app start, boot, and just after midnight ([DayReceiver]) — the watch also
 * ignores a list that is not from today, in case every one of those missed.
 *
 * The watch's tick sends `agenda_done:<key>` back over `/wakeproof/command`
 * ([PhoneCommandListenerService]); the watch shows no optimistic state.
 */
object WearAgendaSync {

    private const val TAG = "WearAgendaSync"
    private const val DAY_REQUEST_CODE = 52_000

    /**
     * Pure: the events the watch gets — today's TIMED events (all-day ones can
     * be neither timed nor focused for), including one that began before
     * midnight and runs into today, earliest first.
     */
    fun watchAgenda(events: List<AgendaEvent>, today: LocalDate, zone: ZoneId): List<AgendaEvent> {
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return events
            .filter { !it.allDay && eventDay(it, zone) <= today && it.endMillis > dayStart }
            .sortedBy { it.beginMillis }
    }

    /**
     * Fire-and-forget [push] for callers outside a coroutine that must not wait
     * on Bluetooth (the done-key write, a receiver).
     */
    fun pushSoon(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { push(app) }
    }

    /**
     * Reads today's events (unless the caller already has the upcoming list)
     * and puts them. Never throws: no watch, no Play Services or no calendar
     * permission are all ordinary.
     */
    suspend fun push(context: Context, upcoming: List<AgendaEvent>? = null) {
        val app = context.applicationContext
        runCatching {
            val repository = AgendaRepository(app)
            val allowed = repository.hasPermission()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val events = if (allowed) watchAgenda(upcoming ?: repository.upcomingEvents(), today, zone)
            else emptyList()
            val keys = events.map { it.key }
            val done = repository.doneKeys.first().filter { it in keys }
            val request = PutDataMapRequest.create(WearContract.AGENDA_PATH).apply {
                dataMap.putLong(WearContract.KEY_AGENDA_DAY, today.toEpochDay())
                dataMap.putBoolean(WearContract.KEY_AGENDA_ALLOWED, allowed)
                dataMap.putStringArrayList(WearContract.KEY_AGENDA_KEYS, ArrayList(keys))
                dataMap.putStringArrayList(WearContract.KEY_AGENDA_TITLES, ArrayList(events.map { it.title }))
                dataMap.putLongArray(WearContract.KEY_AGENDA_BEGINS, events.map { it.beginMillis }.toLongArray())
                dataMap.putLongArray(WearContract.KEY_AGENDA_ENDS, events.map { it.endMillis }.toLongArray())
                dataMap.putStringArrayList(WearContract.KEY_AGENDA_DONE, ArrayList(done))
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(app).putDataItem(request).await()
        }.onFailure { Log.w(TAG, "agenda push failed", it) }
        armMidnight(app)
    }

    /** One inexact alarm just after the next local midnight, re-armed by every push. */
    private fun armMidnight(context: Context) {
        runCatching {
            val zone = ZoneId.systemDefault()
            val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 60_000L
            val pending = PendingIntent.getBroadcast(
                context, DAY_REQUEST_CODE, Intent(context, DayReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }.onFailure { Log.w(TAG, "could not arm the midnight agenda push", it) }
    }

    /** A new day: yesterday's list is off the watch, today's goes on. */
    class DayReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    push(app)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
