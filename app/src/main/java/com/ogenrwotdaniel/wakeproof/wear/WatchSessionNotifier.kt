package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimer
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimerRules
import com.ogenrwotdaniel.wakeproof.data.sync.WatchSessionDisplay
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.SharedSession
import com.ogenrwotdaniel.wakeproof.pomodoro.joinableAt

/**
 * "Focus on your watch" as an ongoing notification on this device (2026-11-05),
 * so a session running on the watch shows everywhere, as the phone's own timer
 * does on the watch — not only on Home, and not only with internet. Driven by
 * `AppBlockService`, which is always running and already follows both copies
 * of the watch timer (Bluetooth and cloud).
 *
 * Counts down by itself (a chronometer to the phase end), so it is re-posted
 * only when what it says changes, never per second.
 */
object WatchSessionNotifier {

    private const val TAG = "WatchSessionNotifier"
    private const val CHANNEL_ID = "wakeproof_watch_session"
    private const val NOTIFICATION_ID = 45

    /** What was last posted, so an unchanged state costs nothing. */
    @Volatile private var lastKey: String? = null

    /** The timer last given, for [refresh]. */
    @Volatile private var lastTimer: LiveWatchTimer? = null

    /** Judges the last timer again: this device has just joined the session, or its own ended. */
    fun refresh(context: Context) = update(context, lastTimer)

    /** Posts, updates or clears the notification for [timer] as of [now]. */
    fun update(context: Context, timer: LiveWatchTimer?, now: Long = System.currentTimeMillis()) {
        lastTimer = timer
        // Joined here: this device's own timer notification already shows the session.
        val joinedHere = timer != null && timer.sessionId.isNotBlank() &&
            timer.sessionId == PomodoroTimerService.runningSessionId
        val display = if (joinedHere) null else LiveWatchTimerRules.display(timer, now)
        val key = display?.let { contentKey(timer!!, it) + "|" + (joinable(timer, now) != null) }
        if (key == lastKey) return
        Log.i(TAG, "watch session: ${key ?: "none"} (from ${timer?.let { "active=${it.active}" } ?: "no timer"})")
        val manager = context.getSystemService(NotificationManager::class.java)
        if (display == null || timer == null) {
            manager.cancel(NOTIFICATION_ID)
            lastKey = null
            return
        }
        // Checked here, inline: lint does not trace the check through a helper.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Focus on your watch", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "A focus session running on your watch"
                    // WakeProof's own session Do Not Disturb hides the shade and
                    // lets only priority channels through; this is the session
                    // itself, not a distraction from it. Honoured because the app
                    // holds DND access; ignored (harmlessly) otherwise.
                    setBypassDnd(true)
                }
        )
        manager.notify(NOTIFICATION_ID, build(context, timer, display, joinable(timer, now)))
        lastKey = key
    }

    /** The watch's session when this device could run it too right now. */
    private fun joinable(timer: LiveWatchTimer, now: Long): SharedSession? =
        timer.toShared()?.takeIf { it.joinableAt(now) && !PomodoroTimerService.state.value.active }

    private fun build(
        context: Context,
        timer: LiveWatchTimer,
        display: WatchSessionDisplay,
        joinable: SharedSession?
    ): Notification {
        val title = "${display.phaseLabel} on your watch" + if (display.running) "" else " (paused)"
        val detail = listOfNotNull(
            if (display.awaitingWatch) "Waiting for the watch…" else display.blockLabel,
            timer.subject
        ).joinToString(" · ")
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 45, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        // The same session on this device, with its ring, sound and tree. The intent
        // names this phase's end; the notification is posted again at every phase.
        joinable?.let { shared ->
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Continue here",
                    PendingIntent.getForegroundService(
                        context, 46, PomodoroTimerService.joinIntent(context, shared),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
        }
        if (display.running && !display.awaitingWatch) {
            builder.setWhen(timer.phaseEndsAt).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
            if (!display.running) {
                val s = display.remainingSeconds
                builder.setSubText("%d:%02d left".format(s / 60, s % 60))
            }
        }
        return builder.build()
    }

    /** Everything the notification shows except the ticking seconds. */
    private fun contentKey(timer: LiveWatchTimer, display: WatchSessionDisplay): String =
        listOf(
            display.phaseLabel, display.running, display.blockLabel, display.awaitingWatch,
            timer.phaseEndsAt, timer.subject.orEmpty(),
            if (display.running) 0 else display.remainingSeconds
        ).joinToString("|")
}
