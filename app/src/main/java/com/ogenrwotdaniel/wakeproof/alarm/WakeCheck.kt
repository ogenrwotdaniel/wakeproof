package com.ogenrwotdaniel.wakeproof.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ogenrwotdaniel.wakeproof.R
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wake-up check: [DELAY_MINUTES] after an alarm is verified, a notification
 * asks "still up?". Answer it and that is the end of it; leave it for
 * [ANSWER_MINUTES] and the alarm rings again, verification and all.
 *
 * Verification proves you were awake for a minute, not that you stayed up — and
 * crawling back into bed straight after is how most alarms are beaten. This is
 * the second half of the promise the app makes.
 *
 * Three stages, all through [AlarmScheduler] so a companion device never takes
 * part: verification arms the PROMPT, the prompt posts the notification and arms
 * the RE-RING, and the "I'm up" action cancels the re-ring. The re-ring is an
 * ordinary [AlarmReceiver] fire, so it gets every ghost and role check the daily
 * alarm does.
 *
 * Once per alarm per day ([WakeCheckStore]): a re-ring that is then verified
 * must not arm another check, or ignoring the notification would chain alarms
 * all morning.
 */
object WakeCheck {
    const val DELAY_MINUTES = 5
    const val ANSWER_MINUTES = 2

    internal const val ACTION_PROMPT = "com.ogenrwotdaniel.wakeproof.action.WAKE_CHECK_PROMPT"
    internal const val ACTION_CONFIRM = "com.ogenrwotdaniel.wakeproof.action.WAKE_CHECK_CONFIRM"

    private const val CHANNEL_ID = "wakeproof_wake_check"
    private const val NOTIFICATION_ID_BASE = 50_000

    fun notificationId(alarmId: Long): Int = NOTIFICATION_ID_BASE + alarmId.toInt()

    internal fun post(context: Context, alarmId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wake-up check",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Asks if you're still up a few minutes after an alarm"
            }
        )
        val confirm = PendingIntent.getBroadcast(
            context,
            notificationId(alarmId),
            Intent(context, WakeCheckReceiver::class.java)
                .setAction(ACTION_CONFIRM)
                .putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Still up?")
            .setContentText("Tap within $ANSWER_MINUTES minutes or the alarm rings again.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(confirm)
            .addAction(Notification.Action.Builder(null, "I'm up", confirm).build())
            .setAutoCancel(true)
            .setTimeoutAfter(ANSWER_MINUTES * 60_000L)
            .build()
        // POST_NOTIFICATIONS can be revoked; then the check silently degrades to
        // a plain re-ring, which still does its job of getting the user up.
        runCatching { manager.notify(notificationId(alarmId), notification) }
    }

    internal fun clear(context: Context, alarmId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(alarmId))
    }
}

/**
 * Remembers which alarms have had today's wake-up check. Device-protected, like
 * [SnoozeStore], because a verification can happen before the first unlock.
 */
@Singleton
class WakeCheckStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("alarm_wake_checks", Context.MODE_PRIVATE)

    /** True — and records it — if [alarmId] has not been checked today yet. */
    fun claimToday(alarmId: Long): Boolean {
        val today = LocalDate.now().toEpochDay()
        if (wakeCheckDoneOn(prefs.getString(alarmId.toString(), null), today)) return false
        prefs.edit { putString(alarmId.toString(), today.toString()) }
        return true
    }
}

/**
 * Whether [stored] says a check already ran on [today]. Unreadable reads as
 * "not yet" — the cost of that is one extra notification, where the opposite
 * mistake would quietly switch the feature off.
 */
internal fun wakeCheckDoneOn(stored: String?, today: Long): Boolean =
    stored?.toLongOrNull() == today

/** Handles the two wake-up check stages that are not an ordinary alarm fire. */
@AndroidEntryPoint
class WakeCheckReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: AlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId == -1L) return
        when (intent.action) {
            WakeCheck.ACTION_PROMPT -> {
                // Arm the re-ring BEFORE posting: if posting fails the user is
                // still checked, just without the chance to answer.
                if (scheduler.scheduleWakeCheckReRing(alarmId)) {
                    WakeCheck.post(context, alarmId)
                }
            }
            WakeCheck.ACTION_CONFIRM -> {
                scheduler.cancelWakeCheck(alarmId)
                WakeCheck.clear(context, alarmId)
            }
        }
    }
}
