package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Data-layer contract shared with the phone app. Keys MUST match
 * `com.ogenrwotdaniel.wakeproof.wear.WearContract` in the `:app` module (kept in
 * sync by hand — there is no shared module).
 */
private object WearContract {
    const val PATH = "/wakeproof/stats"
    const val KEY_STREAK = "streak"
    const val KEY_ASTERISK = "asterisk"
    const val KEY_FOCUS = "focus_minutes"
    const val KEY_FOCUS_GOAL = "focus_goal"
    const val KEY_WATER_COUNT = "water_count"
    const val KEY_WATER_TARGET = "water_target"
    const val KEY_WATER_ML = "water_ml"
}

/** Today's snapshot as mirrored from the phone; [present] is false before any push. */
data class WearStats(
    val streak: Int = 0,
    val asterisk: Boolean = false,
    val focusMinutes: Int = 0,
    val focusGoal: Int = 0,
    val waterCount: Int = 0,
    val waterTarget: Int = 3,
    val waterMl: Int = 0,
    val present: Boolean = false
)

/** Reads the latest [WearStats] the phone pushed onto the Data Layer. */
object WearStatsReader {
    suspend fun read(context: Context): WearStats = withContext(Dispatchers.IO) {
        val buffer = runCatching {
            Wearable.getDataClient(context).dataItems.await()
        }.getOrNull() ?: return@withContext WearStats()
        try {
            val item = buffer.firstOrNull { it.uri.path == WearContract.PATH }
                ?: return@withContext WearStats()
            val map = DataMapItem.fromDataItem(item).dataMap
            WearStats(
                streak = map.getInt(WearContract.KEY_STREAK, 0),
                asterisk = map.getBoolean(WearContract.KEY_ASTERISK, false),
                focusMinutes = map.getInt(WearContract.KEY_FOCUS, 0),
                focusGoal = map.getInt(WearContract.KEY_FOCUS_GOAL, 0),
                waterCount = map.getInt(WearContract.KEY_WATER_COUNT, 0),
                waterTarget = map.getInt(WearContract.KEY_WATER_TARGET, 3),
                waterMl = map.getInt(WearContract.KEY_WATER_ML, 0),
                present = true
            )
        } finally {
            buffer.release()
        }
    }
}
