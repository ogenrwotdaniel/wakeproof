package com.ogenrwotdaniel.wakeproof.agenda

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.alarm.AlarmDevicePolicy
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.data.AgendaRepository
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.SessionEvent
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A reminder [EVENT_REMINDER_LEAD_MILLIS] before every timed calendar event
 * (2026-11-01), offering to focus for it. One exact alarm, for the next
 * reminder only; firing re-arms the one after. Re-armed also from app start,
 * boot and each worker pass, which is what catches an event added or moved
 * in the calendar in the meantime (the provider has no cheap change callback
 * worth a running service).
 *
 * Posted only on the device that rings alarms ([AlarmDevicePolicy]): the
 * calendar is the same on the phone and the tablet, and two reminders for one
 * lecture was exactly the complaint that made alarms ring on one device.
 */
object EventReminders {

    private const val TAG = "EventReminders"
    private const val CHANNEL_ID = "wakeproof_events"
    private const val REQUEST_CODE = 50_000
    private const val CHECK_IN_ID_BASE = 51_000

    /** Arms (or cancels) the alarm for the next reminder. Safe to call from anywhere. */
    suspend fun reschedule(context: Context) {
        val app = context.applicationContext
        val pending = alarmIntent(app)
        val alarms = app.getSystemService(AlarmManager::class.java)
        val repository = AgendaRepository(app)
        val at = if (!AlarmDevicePolicy(app).ringsAlarms || !repository.hasPermission()) null
        else runCatching {
            nextReminderAt(repository.upcomingEvents(), repository.doneKeys.first(), System.currentTimeMillis())
        }.getOrNull()
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        arm(alarms, at, pending)
    }

    // USE_EXACT_ALARM is declared (an alarm clock app), which covers this.
    @SuppressLint("MissingPermission")
    private fun arm(alarms: AlarmManager, at: Long, pending: PendingIntent) {
        runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .recoverCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure { Log.w(TAG, "could not arm the event reminder", it) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, EventReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Posts the reminders due now, then arms the next. */
    internal suspend fun fire(context: Context) {
        val app = context.applicationContext
        val repository = AgendaRepository(app)
        if (AlarmDevicePolicy(app).ringsAlarms && repository.hasPermission()) {
            val due = runCatching {
                eventsToRemind(repository.upcomingEvents(), repository.doneKeys.first(), System.currentTimeMillis())
            }.getOrDefault(emptyList())
            due.forEach { postReminder(app, it) }
        }
        reschedule(app)
    }

    private fun postReminder(context: Context, event: AgendaEvent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val start = Instant.ofEpochMilli(event.beginMillis).atZone(ZoneId.systemDefault())
        val focus = PendingIntent.getActivity(
            context, event.key.hashCode(), EventFocusActivity.intent(context, event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .setContentText("Starts at ${formatAlarmTime(context, start.hour, start.minute)}")
            .setAutoCancel(true)
            .setTimeoutAfter((event.endMillis - System.currentTimeMillis()).coerceAtLeast(60_000L))
        // Nothing to offer while a session already runs — it cannot be stopped
        // to start another — or when the event is too short to focus for.
        val minutes = eventFocusMinutes(event, event.beginMillis)
        if (!PomodoroTimerService.state.value.active && minutes != null) {
            builder.setContentIntent(focus).addAction(
                Notification.Action.Builder(null, "Focus for this event", focus).build()
            )
        }
        manager.notify(notificationId(event.key), builder.build())
    }

    fun cancelNotification(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(key))
    }

    private fun notificationId(key: String) = REQUEST_CODE + 1 + (key.hashCode() and 0x3FF)

    /**
     * "Done what you planned?" when an event's session ends (2026-11-01) — one
     * tap ticks the event off the agenda, the same tick as the home screen's.
     */
    fun postCheckIn(context: Context, event: SessionEvent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val id = CHECK_IN_ID_BASE + (event.key.hashCode() and 0x3FF)
        val tick = PendingIntent.getBroadcast(
            context, id,
            Intent(context, EventDoneReceiver::class.java)
                .putExtra(EventDoneReceiver.EXTRA_KEY, event.key)
                .putExtra(EventDoneReceiver.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            id,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Done what you planned?")
                .setContentText(event.title.ifBlank { "Your calendar event" })
                .addAction(Notification.Action.Builder(null, "Tick it off", tick).build())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Calendar events", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A reminder before each event, with a way to focus for it" }
        )
    }
}

/** The next event reminder is due; see [EventReminders]. */
class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                EventReminders.fire(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Tick it off" on the end-of-event check-in. */
class EventDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        context.getSystemService(NotificationManager::class.java)
            .cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AgendaRepository(context.applicationContext).markDone(key)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
