package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Timer contract keys — MUST match app `WearContract` timer keys (kept in sync by hand). */
internal object TimerContract {
    const val PATH = "/wakeproof/timer"
    const val KEY_ACTIVE = "t_active"
    const val KEY_RUNNING = "t_running"
    const val KEY_PHASE = "t_phase"
    const val KEY_REMAINING = "t_remaining"
    const val KEY_FINISH_AT = "t_finish_at"
    /** The phone's wall clock at the push (app `WearContract.KEY_UPDATED`, shared with stats). */
    const val KEY_UPDATED = "updated_at"

    // The phone's session itself (2026-11-11): app `WearContract.KEY_TIMER_*`.
    const val KEY_SESSION_ID = "t_session_id"
    const val KEY_ORIGIN = "t_origin"
    const val KEY_PHASE_NAME = "t_phase_name"
    const val KEY_COMPLETED = "t_completed"
    const val KEY_TARGET = "t_target"
    const val KEY_FOCUS_SEC = "t_focus_sec"
    const val KEY_SHORT_SEC = "t_short_sec"
    const val KEY_LONG_SEC = "t_long_sec"
    const val KEY_LONG_EVERY = "t_long_every"
    const val KEY_LAST_FOCUS_SEC = "t_last_focus_sec"
    const val KEY_SUBJECT = "t_subject"
}

/** A single push of Pomodoro timer state from the phone. */
data class TimerSnapshot(
    val active: Boolean,
    val running: Boolean,
    val phase: String,
    val remainingSeconds: Int,
    val finishAtMillis: Long,
    /** When the phone pushed this, its clock; 0 = unknown (older phone build). */
    val updatedAtMillis: Long = 0L,
    /** The session itself, when the phone sent one: what "Continue here" runs. */
    val session: PhoneSession? = null
) {
    /**
     * Pure: whether this mirror still describes a live phone session at [now].
     *
     * The phone pushes on every transition, and `active = false` when a session
     * ends — but a phone whose process is KILLED mid-session never sends that
     * last push, and the watch mirrored the dead session forever: no buttons,
     * no way in (seen on hardware 2026-11-04). So a mirror is treated as ended
     * when it is running and its phase finished more than
     * [RUNNING_STALE_MILLIS] ago (a live phone would have pushed the next
     * phase by then), or paused and pushed more than [PAUSED_STALE_MILLIS] ago.
     */
    fun liveAt(now: Long): Boolean {
        if (!active) return false
        return if (running) {
            finishAtMillis <= 0L || now - finishAtMillis <= RUNNING_STALE_MILLIS
        } else {
            updatedAtMillis <= 0L || now - updatedAtMillis <= PAUSED_STALE_MILLIS
        }
    }

    companion object {
        fun from(map: DataMap) = TimerSnapshot(
            active = map.getBoolean(TimerContract.KEY_ACTIVE, false),
            running = map.getBoolean(TimerContract.KEY_RUNNING, false),
            phase = map.getString(TimerContract.KEY_PHASE, ""),
            remainingSeconds = map.getInt(TimerContract.KEY_REMAINING, 0),
            finishAtMillis = map.getLong(TimerContract.KEY_FINISH_AT, 0L),
            updatedAtMillis = map.getLong(TimerContract.KEY_UPDATED, 0L),
            session = map.getString(TimerContract.KEY_SESSION_ID)?.takeIf { it.isNotBlank() }?.let { id ->
                PhoneSession(
                    sessionId = id,
                    origin = map.getString(TimerContract.KEY_ORIGIN).orEmpty(),
                    phaseName = map.getString(TimerContract.KEY_PHASE_NAME).orEmpty(),
                    phaseEndsAt = map.getLong(TimerContract.KEY_FINISH_AT, 0L),
                    completedFocus = map.getInt(TimerContract.KEY_COMPLETED, 0),
                    targetFocusBlocks = map.getInt(TimerContract.KEY_TARGET, 0),
                    focusSeconds = map.getInt(TimerContract.KEY_FOCUS_SEC, 0),
                    shortBreakSeconds = map.getInt(TimerContract.KEY_SHORT_SEC, 0),
                    longBreakSeconds = map.getInt(TimerContract.KEY_LONG_SEC, 0),
                    longBreakEvery = map.getInt(TimerContract.KEY_LONG_EVERY, 0),
                    lastFocusSeconds = map.getInt(TimerContract.KEY_LAST_FOCUS_SEC, 0),
                    subject = map.getString(TimerContract.KEY_SUBJECT)?.takeIf { it.isNotBlank() }
                )
            }
        )

        /** See [liveAt]. */
        const val RUNNING_STALE_MILLIS = 2 * 60_000L
        const val PAUSED_STALE_MILLIS = 12 * 60 * 60_000L

        /**
         * Reads the latest timer snapshot the phone pushed onto the Data Layer,
         * or null if none / not active. NOT filtered for staleness — callers
         * deciding what runs use [liveAt]. Mirrors [WearStatsReader]'s read path so
         * [MainActivity] can show the live countdown while a Pomodoro runs.
         */
        suspend fun read(context: Context): TimerSnapshot? = withContext(Dispatchers.IO) {
            val buffer = runCatching {
                Wearable.getDataClient(context).dataItems.await()
            }.getOrNull() ?: return@withContext null
            try {
                val item = buffer.firstOrNull { it.uri.path == TimerContract.PATH }
                    ?: return@withContext null
                from(DataMapItem.fromDataItem(item).dataMap).takeIf { it.active }
            } finally {
                buffer.release()
            }
        }
    }
}

/** The watch's own timer in the shape the phone's pushes take, so one renderer serves both. */
fun WatchTimerState.toSnapshot(now: Long) = TimerSnapshot(
    active = active,
    running = running,
    phase = phase.label,
    remainingSeconds = remainingSecondsAt(now).toInt(),
    finishAtMillis = phaseEndsAt
)

/**
 * Posts / updates / cancels the watch's Ongoing Activity for the running
 * Pomodoro. When running, a [Status.TimerPart] anchored to the finish time
 * counts down on the watch itself — smooth ticking with no per-second sync.
 *
 * There is one item and two possible timers: the watch's own
 * ([WatchTimerEngine]) and the phone's mirror. The watch's wins — it is the one
 * the user is wearing and started here — and [refresh] is the only place that
 * decides, so a phone push cannot clear a chip the watch timer still needs.
 */
object TimerNotifier {
    private const val CHANNEL_ID = "wakeproof_timer"
    private const val NOTIFICATION_ID = 42

    /** Shows whichever timer is live, preferring the watch's own. */
    suspend fun refresh(context: Context) {
        val local = WatchTimerEngine.state(context)
        if (local.active) {
            update(context, local.toSnapshot(System.currentTimeMillis()))
        } else {
            update(context, TimerSnapshot.read(context)?.takeIf { it.liveAt(System.currentTimeMillis()) })
        }
    }

    fun update(context: Context, snapshot: TimerSnapshot?) {
        val manager = NotificationManagerCompat.from(context)
        if (snapshot == null || !snapshot.active) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        // POST_NOTIFICATIONS is declared, but the watch asks for it at runtime and
        // the user can say no — in which case there is no Now Bar item to post and
        // the whole build-up below is wasted work. Cancelling first keeps a stale
        // chip from outliving a revoked grant. Inlined rather than extracted to a
        // helper because lint only credits the guard inside the same function.
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(context)
        val touchIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val phase = snapshot.phase.ifBlank { "Focus" }
        val status = if (snapshot.running) {
            Status.Builder()
                .addTemplate("$phase #timer#")
                .addPart("timer", Status.TimerPart(snapshot.finishAtMillis))
                .build()
        } else {
            val minutes = snapshot.remainingSeconds / 60
            val seconds = snapshot.remainingSeconds % 60
            Status.Builder()
                .addTemplate("$phase paused #time#")
                .addPart("time", Status.TextPart("%d:%02d".format(minutes, seconds)))
                .build()
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Euler's Lab")
            .setContentText(if (snapshot.running) phase else "$phase (paused)")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_foreground)
            .setTouchIntent(touchIntent)
            .setStatus(status)
            .build()
            .apply(context)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * "Break — 5 min" / "Focus — block 2 of 4", posted the moment a phase
     * changes, on a HIGH-importance channel so it lights the screen and peeks
     * over the watch face. The quiet ongoing chip alone was too easy to miss
     * on a raise-to-wake watch that is face-down on a desk.
     *
     * Silent on purpose — the engine's alarm-strength buzz is the vibration,
     * and the channel adding its own would double it. Times out after two
     * minutes so a desk of finished phases does not stack up in the tray.
     */
    fun phaseAlert(context: Context, state: WatchTimerState) {
        // Inlined grant check: lint only credits the guard in the same function.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val minutes = (state.durations.millisOf(state.phase) / 60_000L).toInt()
        val until = android.text.format.DateFormat.getTimeFormat(context)
            .format(java.util.Date(state.phaseEndsAt))
        val title: String
        val text: String
        if (state.phase == WatchPhase.FOCUS) {
            val block = state.completedFocus + 1
            val of = if (state.targetFocusBlocks > 0) " of ${state.targetFocusBlocks}" else ""
            title = "Focus — block $block$of"
            text = "Back to it. Break at $until."
        } else {
            title = "${state.phase.label} — $minutes min"
            text = "Step away from the screen. Focus again at $until."
        }
        postAlert(context, PHASE_ALERT_ID, title, text, stopsBuzz = true)
    }

    /**
     * The end of a session, with what it added up to: "Session complete" when a
     * timed session ran out, "Session ended" when the user stopped it (which is
     * how a free run always ends). Carries today's total against the goal, so
     * the last thing a session says is how the day stands.
     */
    fun sessionSummary(context: Context, complete: Boolean, blocks: Int, minutes: Int, day: FocusDay?) {
        // The last phase alert is stale now; this replaces it.
        clearPhaseAlert(context)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        postAlert(
            context,
            SESSION_DONE_ID,
            if (complete) "Session complete" else "Session ended",
            sessionSummary(blocks, minutes, day),
            // Only a session that ran out is buzzing; a stop was a tap.
            stopsBuzz = complete
        )
    }

    /**
     * Today's focus reached the daily goal — which is what makes the day count
     * toward the streak (the streak is focus-only). Silent: it lands with a
     * phase-change buzz that is already playing.
     */
    fun goalReached(context: Context, day: FocusDay) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        postAlert(
            context,
            GOAL_REACHED_ID,
            "Daily focus goal reached",
            "${formatFocusMinutes(day.minutes)} of ${formatFocusMinutes(day.goal)} today. This day counts toward your streak.",
            stopsBuzz = false
        )
    }

    fun clearPhaseAlert(context: Context) {
        NotificationManagerCompat.from(context).cancel(PHASE_ALERT_ID)
    }

    /**
     * Posts a peeking alert. [stopsBuzz] adds "Got it", for an alert that
     * arrives with a long buzz; swiping any alert away also stops the buzz,
     * since that is the user acknowledging it just the same.
     */
    @android.annotation.SuppressLint("MissingPermission") // every caller checks the grant inline
    private fun postAlert(context: Context, id: Int, title: String, text: String, stopsBuzz: Boolean) {
        ensureAlertChannel(context)
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Request code = notification id, so each alert keeps its own extra.
        val stopBuzz = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, StopBuzzReceiver::class.java)
                .putExtra(StopBuzzReceiver.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setDeleteIntent(stopBuzz)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_TIMEOUT_MS)
        if (stopsBuzz) builder.addAction(0, "Got it", stopBuzz)
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private const val SESSION_DONE_ID = 43
    private const val PHASE_ALERT_ID = 44
    private const val GOAL_REACHED_ID = 45
    private const val ALERT_CHANNEL_ID = "wakeproof_phase"
    private const val ALERT_TIMEOUT_MS = 2 * 60_000L

    private fun ensureAlertChannel(context: Context) {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID, "Focus / break changes", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Lights the screen when a focus block or break starts"
            // The engine vibrates at alarm strength itself; see phaseAlert.
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Pomodoro timer", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Live focus timer, from the watch or the phone" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
