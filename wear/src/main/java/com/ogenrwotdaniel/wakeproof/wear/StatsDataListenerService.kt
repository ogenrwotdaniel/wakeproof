package com.ogenrwotdaniel.wakeproof.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wakes on every Data Layer change under `/wakeproof` and asks the system to
 * re-render [StatsTileService], so the tile reflects fresh phone stats without
 * waiting for its periodic refresh.
 */
class StatsDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.release()
        TileService.getUpdater(this).requestUpdate(StatsTileService::class.java)
    }
}
