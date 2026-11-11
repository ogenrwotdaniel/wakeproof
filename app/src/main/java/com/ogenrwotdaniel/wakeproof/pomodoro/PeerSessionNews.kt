package com.ogenrwotdaniel.wakeproof.pomodoro

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.R

/** Something worth telling the user about a session running on another of their devices. */
enum class PeerNews { STARTED, INTO_BREAK, INTO_FOCUS, FINISHED }

/**
 * Pure: what just happened to the session on another device, given the copy
 * heard before ([previous]) and now ([current]); null for nothing new.
 *
 * Added 2026-11-11 (the user: "I only get notified on watch while working on
 * tablet and end up not realising anything"): a phase end rang only on the
 * device that ran the timer.
 *
 * - A copy of a session further on than the last one heard is a phase change.
 *   The same position again (the Bluetooth and cloud copies both arrive, a
 *   realign) is not, and nor is an older copy.
 * - A session first heard of in its opening minute has just started. Later
 *   than that it is this device catching up (a restart, a reconnect), which is
 *   not news.
 * - A session gone while in its last block, around that block's end, finished.
 *   Gone at any other moment is a lost connection, and says nothing.
 */
fun peerNews(previous: SharedSession?, current: SharedSession?, now: Long): PeerNews? = when {
    current == null -> PeerNews.FINISHED.takeIf {
        previous != null && previous.phase == PomodoroPhase.FOCUS &&
            previous.completedFocus + 1 >= previous.targetFocusBlocks &&
            kotlin.math.abs(now - previous.phaseEndsAtMillis) <= FINISH_WINDOW_MILLIS
    }
    previous?.id == current.id ->
        if (current.position <= previous.position) null
        else if (current.phase == PomodoroPhase.FOCUS) PeerNews.INTO_FOCUS else PeerNews.INTO_BREAK
    else -> PeerNews.STARTED.takeIf {
        current.position == 0 &&
            now - (current.phaseEndsAtMillis - current.focusSeconds * 1_000L) in -START_WINDOW_MILLIS..START_WINDOW_MILLIS
    }
}

/** How close to its last block's end a vanished session counts as finished. */
const val FINISH_WINDOW_MILLIS = 3 * 60_000L

/** How long after it began a newly heard session still counts as just started. */
const val START_WINDOW_MILLIS = 90_000L

/**
 * Posts [PeerNews] on this device: a heads-up with the soft focus chime and a
 * buzz, so a phase ending on the watch is heard at the tablet. Not for a
 * session this device runs too; its own timer rings for that.
 */
object PeerSessionNotifier {
    private const val CHANNEL_ID = "wakeproof_peer_session_news"
    private const val NOTIFICATION_ID = 47

    fun post(context: Context, news: PeerNews, session: SharedSession) {
        if (session.id == PomodoroTimerService.runningSessionId) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Focus on your other devices", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A focus session on your watch or other device started, changed phase or finished"
                setSound(
                    Uri.parse("android.resource://${context.packageName}/${R.raw.focus_chime}"),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                // The session's own Do Not Disturb is on here; this is the session, not a distraction.
                setBypassDnd(true)
            }
        )
        val where = if (session.origin == SharedSession.ORIGIN_WATCH) "your watch" else "your other device"
        val block = if (session.phase == PomodoroPhase.FOCUS) session.completedFocus + 1 else session.completedFocus
        val title = when (news) {
            PeerNews.STARTED -> "Focus started on $where"
            PeerNews.INTO_BREAK -> "Break time"
            PeerNews.INTO_FOCUS -> "Back to focus"
            PeerNews.FINISHED -> "Session complete"
        }
        val text = when (news) {
            PeerNews.FINISHED -> "The session on $where finished. The focus wall is down."
            PeerNews.INTO_BREAK -> "Block $block of ${session.targetFocusBlocks} done on $where."
            else -> "Block $block of ${session.targetFocusBlocks} on $where."
        }
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 47, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
        )
    }
}
