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
 *   [TimerNotifier] (deleted item or active=false clears it).
 */
class StatsDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var refreshTile = false
        var timerChanged = false
        var timer: TimerSnapshot? = null
        for (event in dataEvents) {
            when (event.dataItem.uri.path) {
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
        if (timerChanged) {
            TimerNotifier.update(this, timer)
        }
    }
}

/** Shared with [WearStatsReader]; the stats DataItem path. */
internal object WearStatsPath {
    const val STATS = "/wakeproof/stats"
}
