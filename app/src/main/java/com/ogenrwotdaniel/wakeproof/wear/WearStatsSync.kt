package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Contract shared with the `:wear` module's tile. The data-layer path and keys
 * MUST match [com.ogenrwotdaniel.wakeproof.wear] on the watch side (there is no
 * shared module, so the two copies are kept in sync by hand).
 */
object WearContract {
    const val PATH = "/wakeproof/stats"
    const val KEY_STREAK = "streak"
    /** No longer written: the streak is focus-only since 1.5, so the watch reads false. */
    const val KEY_ASTERISK = "asterisk"
    const val KEY_FOCUS = "focus_minutes"
    const val KEY_FOCUS_GOAL = "focus_goal"
    const val KEY_WATER_COUNT = "water_count"
    const val KEY_WATER_TARGET = "water_target"
    const val KEY_WATER_ML = "water_ml"
    const val KEY_UPDATED = "updated_at"

    // Live Pomodoro timer mirrored to the watch's Ongoing Activity.
    const val TIMER_PATH = "/wakeproof/timer"
    const val KEY_TIMER_ACTIVE = "t_active"
    const val KEY_TIMER_RUNNING = "t_running"
    const val KEY_TIMER_PHASE = "t_phase"
    const val KEY_TIMER_REMAINING = "t_remaining"
    const val KEY_TIMER_FINISH_AT = "t_finish_at"

    /**
     * Watch -> phone commands, carried on the MessageClient rather than the
     * DataClient.
     *
     * The distinction is load-bearing. A DataItem is *state*: writing the same
     * value twice changes nothing and fires no event, so "pause" tapped twice
     * after a resume would be silently swallowed. A command is an *event*, and
     * MessageClient delivers every one of them.
     *
     * The timer is the only thing the watch may drive. Snoozing or dismissing
     * an alarm from the wrist is deliberately NOT here: the app's whole promise
     * is an alarm you cannot escape without getting up, and a wrist control
     * hands the sleeper an escape without leaving the bed. It would also route
     * the one thing that must not fail through a Bluetooth link that is
     * regularly out of range or flat.
     */
    const val COMMAND_PATH = "/wakeproof/command"
    const val CMD_TIMER_START = "timer_start"
    const val CMD_TIMER_PAUSE = "timer_pause"
    const val CMD_TIMER_RESUME = "timer_resume"
    const val CMD_TIMER_STOP = "timer_stop"
}

/**
 * Mirrors today's progress (streak, focus vs goal, water) onto the Wearable
 * Data Layer so the Galaxy Watch tile can show it. A single persisted DataItem
 * at [WearContract.PATH]; the watch reads the last snapshot even while the phone
 * is asleep. No-ops gracefully when Play Services or a watch is unavailable.
 */
@Singleton
class WearStatsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository
) {
    private val dataClient by lazy { Wearable.getDataClient(context) }

    suspend fun push() {
        val today = statsRepository.observeToday().first()
        val streak = statsRepository.observeStreak().first()
        val settings = settingsRepository.settings.first()
        val request = PutDataMapRequest.create(WearContract.PATH).apply {
            dataMap.putInt(WearContract.KEY_STREAK, streak.days)
            dataMap.putInt(WearContract.KEY_FOCUS, today.focusMinutes)
            dataMap.putInt(WearContract.KEY_FOCUS_GOAL, settings.dailyFocusGoalMinutes)
            dataMap.putInt(WearContract.KEY_WATER_COUNT, today.waterCount)
            dataMap.putInt(WearContract.KEY_WATER_TARGET, today.waterTargetCount)
            dataMap.putInt(WearContract.KEY_WATER_ML, today.waterMl)
            // Force a distinct DataItem so the watch is notified even when the
            // visible numbers happen to be identical to the last push.
            dataMap.putLong(WearContract.KEY_UPDATED, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { dataClient.putDataItem(request).await() }
    }
}
