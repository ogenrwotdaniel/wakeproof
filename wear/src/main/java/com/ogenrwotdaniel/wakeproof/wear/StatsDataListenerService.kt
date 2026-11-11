package com.ogenrwotdaniel.wakeproof.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Reacts to Data Layer pushes from the phone:
 * - `/wakeproof/stats` → re-render [StatsTileService].
 * - `/wakeproof/timer` → post/update/cancel the timer Ongoing Activity via
 *   [TimerNotifier] (deleted item or active=false clears it) — unless the
 *   watch's own timer is running, which keeps the chip.
 * - `/wakeproof/alarm` → buzz the wrist while the phone's alarm rings
 *   ([PhoneAlarmMirror]); it cannot be dismissed here.
 * - `/wakeproof/agenda` (and `/wakeproof/timer`) → re-render the agenda tile and
 *   complication ([AgendaSurfaces]).
 * - `/wakeproof/session/…` deleted → the phone imported a block finished here;
 *   the tile's "waiting to sync" count and focus total change with it.
 */
class StatsDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var refreshTile = false
        var refreshAgendaTile = false
        var timerChanged = false
        var timer: TimerSnapshot? = null
        for (event in dataEvents) {
            val path = event.dataItem.uri.path.orEmpty()
            if (path.startsWith(SessionContract.PATH_PREFIX)) {
                refreshTile = true
                continue
            }
            when (path) {
                WearStatsPath.STATS -> refreshTile = true
                // Today's calendar: the "WakeProof Today" tile lists it.
                AgendaContract.PATH -> refreshAgendaTile = true
                // The locked times, for the watch's backup alarm.
                AlarmScheduleContract.PATH -> BackupAlarm.onSchedule(
                    this,
                    if (event.type == DataEvent.TYPE_CHANGED) {
                        DataMapItem.fromDataItem(event.dataItem).dataMap
                    } else {
                        null
                    }
                )
                // The phone's alarm rings here too, as a buzz; see PhoneAlarmMirror.
                PhoneAlarmContract.PATH -> PhoneAlarmMirror.onChanged(
                    this,
                    if (event.type == DataEvent.TYPE_CHANGED) {
                        DataMapItem.fromDataItem(event.dataItem).dataMap
                    } else {
                        null
                    }
                )
                TimerContract.PATH -> {
                    timerChanged = true
                    // The agenda tile says when a phone session runs.
                    refreshAgendaTile = true
                    timer = if (event.type == DataEvent.TYPE_CHANGED) {
                        TimerSnapshot.from(DataMapItem.fromDataItem(event.dataItem).dataMap)
                    } else {
                        null
                    }
                }
            }
        }
        dataEvents.release()

        if (refreshTile) {
            TileService.getUpdater(this).requestUpdate(StatsTileService::class.java)
        }
        if (refreshAgendaTile) AgendaSurfaces.refresh(this)
        if (timerChanged && !WatchTimerEngine.state(this).active) {
            // The phone's phase end is felt on the wrist too (2026-11-11): the user
            // may be at the tablet with the phone across the room.
            val prefs = getSharedPreferences(PHONE_TIMER_PREFS, MODE_PRIVATE)
            val key = phoneTimerKey(timer)
            phoneTimerBuzz(prefs.getString(KEY_LAST_PHONE_TIMER, null), key)?.let { Buzzer.play(this, it) }
            prefs.edit().putString(KEY_LAST_PHONE_TIMER, key).apply()
            TimerNotifier.update(this, timer)
        }
    }
}

/** Shared with [WearStatsReader]; the stats DataItem path. */
internal object WearStatsPath {
    const val STATS = "/wakeproof/stats"
}

private const val PHONE_TIMER_PREFS = "phone_timer_seen"
private const val KEY_LAST_PHONE_TIMER = "last"

/** Pure: what of the phone's timer a buzz is keyed on: its phase while running, else nothing. */
fun phoneTimerKey(timer: TimerSnapshot?): String? = timer?.takeIf { it.active && it.running }?.phase

/**
 * Pure: the buzz for the phone's timer going from [last] to [now] ([phoneTimerKey]),
 * or null. The phone pushes the item again inside a phase (a realign, a restart),
 * so only a change of phase counts. Short patterns: the phone rings for itself,
 * this is the echo.
 */
fun phoneTimerBuzz(last: String?, now: String?): LongArray? = when {
    last == now -> null
    now == null -> BuzzPattern.PEER_DONE
    else -> BuzzPattern.PEER_PHASE
}
