package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
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
    // Phase lengths and subject, so a pomodoro started ON THE WATCH (with no
    // phone around) runs the user's own lengths and logs under their subject.
    const val KEY_FOCUS_LEN = "focus_len"
    const val KEY_SHORT_BREAK_LEN = "short_break_len"
    const val KEY_LONG_BREAK_LEN = "long_break_len"
    const val KEY_SUBJECT = "active_subject"

    // Live Pomodoro timer mirrored to the watch's Ongoing Activity.
    const val TIMER_PATH = "/wakeproof/timer"
    const val KEY_TIMER_ACTIVE = "t_active"
    const val KEY_TIMER_RUNNING = "t_running"
    const val KEY_TIMER_PHASE = "t_phase"
    const val KEY_TIMER_REMAINING = "t_remaining"
    const val KEY_TIMER_FINISH_AT = "t_finish_at"

    /**
     * The phone's session on the same item (2026-11-11), so the watch can run
     * it too ("Continue here"); see `SharedSession`. Absent while paused or for
     * a session with no id. MUST match wear `TimerContract`.
     */
    const val KEY_TIMER_SESSION_ID = "t_session_id"
    const val KEY_TIMER_ORIGIN = "t_origin"
    const val KEY_TIMER_PHASE_NAME = "t_phase_name"
    const val KEY_TIMER_COMPLETED = "t_completed"
    const val KEY_TIMER_TARGET = "t_target"
    const val KEY_TIMER_FOCUS_SEC = "t_focus_sec"
    const val KEY_TIMER_SHORT_SEC = "t_short_sec"
    const val KEY_TIMER_LONG_SEC = "t_long_sec"
    const val KEY_TIMER_LONG_EVERY = "t_long_every"
    const val KEY_TIMER_LAST_FOCUS_SEC = "t_last_focus_sec"
    const val KEY_TIMER_SUBJECT = "t_subject"

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

    /**
     * `agenda_done:<event key>` — the watch's tick on a calendar event. The
     * phone marks it done in `AgendaRepository` and re-pushes [AGENDA_PATH];
     * the watch changes nothing until that push lands.
     */
    const val CMD_AGENDA_DONE_PREFIX = "agenda_done:"

    /**
     * Today's timed calendar events, for the watch's "Today" list
     * ([WearAgendaSync]). Parallel arrays, one entry per event; [KEY_AGENDA_DONE]
     * is the subset of keys ticked off. MUST match wear `AgendaContract`.
     */
    const val AGENDA_PATH = "/wakeproof/agenda"
    const val KEY_AGENDA_DAY = "ag_day"
    const val KEY_AGENDA_ALLOWED = "ag_allowed"
    const val KEY_AGENDA_KEYS = "ag_keys"
    const val KEY_AGENDA_TITLES = "ag_titles"
    const val KEY_AGENDA_BEGINS = "ag_begins"
    const val KEY_AGENDA_ENDS = "ag_ends"
    const val KEY_AGENDA_DONE = "ag_done"

    /**
     * Focus blocks finished on the watch's OWN timer, one DataItem each at
     * `SESSION_PATH_PREFIX + <block id>`. The watch writes them whether or not
     * the phone is reachable and the Data Layer delivers them when it is; the
     * phone imports each and then deletes the item, which is the acknowledgement.
     * Mirrors the watch's `SessionContract`.
     */
    const val SESSION_PATH_PREFIX = "/wakeproof/session/"

    /**
     * The watch's running session: one DataItem holding when it ends (watch
     * wall clock), 0 when none. Walls this phone and, relayed through the
     * cloud, the tablet. MUST match wear `WatchSessionContract`.
     */
    const val WATCH_SESSION_PATH = "/wakeproof/watch_session"
    const val KEY_WATCH_SESSION_ENDS_AT = "ws_ends_at"

    /** The watch's live timer on the same item (2026-11-05), read into a `LiveWatchTimer`. */
    const val KEY_WS_ACTIVE = "ws_active"
    const val KEY_WS_RUNNING = "ws_running"
    const val KEY_WS_PHASE = "ws_phase"
    const val KEY_WS_PHASE_ENDS_AT = "ws_phase_ends_at"
    const val KEY_WS_REMAINING = "ws_remaining"
    const val KEY_WS_COMPLETED = "ws_completed"
    const val KEY_WS_TARGET = "ws_target"
    const val KEY_WS_SUBJECT = "ws_subject"
    const val KEY_WS_UPDATED_AT = "ws_updated_at"

    /** The rest of the watch's session (2026-11-11), so the phone can join it; see `SharedSession`. */
    const val KEY_WS_SESSION_ID = "ws_session_id"
    const val KEY_WS_ORIGIN = "ws_origin"
    const val KEY_WS_FOCUS_MIN = "ws_focus_min"
    const val KEY_WS_SHORT_MIN = "ws_short_min"
    const val KEY_WS_LONG_MIN = "ws_long_min"
    const val KEY_WS_LONG_EVERY = "ws_long_every"
    const val KEY_WS_LAST_FOCUS_SEC = "ws_last_focus_sec"
    const val KEY_SESSION_ID = "s_id"
    const val KEY_SESSION_STARTED = "s_started"
    const val KEY_SESSION_ENDED = "s_ended"
    const val KEY_SESSION_MINUTES = "s_minutes"
    const val KEY_SESSION_EPOCH_DAY = "s_epoch_day"
    const val KEY_SESSION_SUBJECT = "s_subject"

    /**
     * On the STATS item: ids of focus blocks the phone holds (last
     * [ACK_WINDOW_DAYS] days). THIS is the acknowledgement. Deleting the
     * session item from the phone is not: a DataItem belongs to the node that
     * wrote it, and a delete from any other node only removes the local copy —
     * the watch kept three imported blocks as "waiting" and counted them twice
     * (2026-10-15). The watch deletes its own items once their ids appear here.
     */
    const val KEY_SESSION_ACKED = "s_acked"
    const val ACK_WINDOW_DAYS = 14L

    /**
     * Phone -> watch, MessageClient: a fresh Google ID token for this device's
     * sync account, so the watch can sign in to the cloud without a picker.
     * Mirrors the watch's `CloudContract.AUTH_PATH`.
     */
    const val AUTH_PATH = "/wakeproof/auth"
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
    private val settingsRepository: SettingsRepository,
    private val completionRepository: CompletionRepository
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
            dataMap.putInt(WearContract.KEY_FOCUS_LEN, settings.focusMinutes)
            dataMap.putInt(WearContract.KEY_SHORT_BREAK_LEN, settings.shortBreakMinutes)
            dataMap.putInt(WearContract.KEY_LONG_BREAK_LEN, settings.longBreakMinutes)
            dataMap.putString(WearContract.KEY_SUBJECT, settings.activeSubject)
            val ackFrom = LocalDate.now().toEpochDay() - WearContract.ACK_WINDOW_DAYS
            dataMap.putStringArrayList(
                WearContract.KEY_SESSION_ACKED,
                ArrayList(completionRepository.focusSyncIdsSince(ackFrom))
            )
            // Force a distinct DataItem so the watch is notified even when the
            // visible numbers happen to be identical to the last push.
            dataMap.putLong(WearContract.KEY_UPDATED, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { dataClient.putDataItem(request).await() }
    }
}
