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
 * - `/wakeproof/session/…` deleted → the phone imported a block finished here;
 *   the tile's "waiting to sync" count and focus total change with it.
 */
class StatsDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var refreshTile = false
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
                TimerContract.PATH -> {
                    timerChanged = true
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
        if (timerChanged && !WatchTimerEngine.state(this).active) {
            TimerNotifier.update(this, timer)
        }
    }
}

/** Shared with [WearStatsReader]; the stats DataItem path. */
internal object WearStatsPath {
    const val STATS = "/wakeproof/stats"
}
