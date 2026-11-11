package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import android.net.Uri
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `/wakeproof/alarm_schedule` — the phone's LOCKED alarm times, as minutes of
 * the day. A hand-kept copy of the phone's `WearAlarmSync` schedule keys.
 */
internal object AlarmScheduleContract {
    const val PATH = "/wakeproof/alarm_schedule"
    const val KEY_MINUTES = "s_minutes"
}

/**
 * The watch as a BACKUP for the phone's locked alarms (2026-11-04). The phone
 * is the single point of failure — flat, switched off, or crashed, and nothing
 * rings at 03:00. The watch knows the locked times (pushed by the phone) and
 * checks [GRACE_MILLIS] after each one: if the phone has not reported a ring
 * since shortly before it, the watch rings on its own — the song on its
 * speaker, an alarm-strength buzz, and a notification saying the phone did
 * not ring. Like every watch buzz it ends after [PHONE_ALARM_BUZZ_MILLIS];
 * there is nothing to verify on the watch, and the point is to get up and get
 * to the phone.
 *
 * One exact alarm-clock alarm for the soonest slot, re-armed every time it
 * fires, on every schedule push, on boot and on app update.
 */
object BackupAlarm {
    private const val TAG = "BackupAlarm"
    private const val PREFS = "backup_alarm"
    private const val KEY_MINUTES = "minutes"
    private const val KEY_LAST_PHONE_RING = "last_phone_ring"
    private const val ACTION_CHECK = "com.ogenrwotdaniel.wakeproof.wear.BACKUP_ALARM_CHECK"
    private const val EXTRA_SLOT_AT = "slot_at"
    private const val NOTIFICATION_ID = 62

    const val GRACE_MILLIS = 2 * 60_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The phone pushed its locked times. */
    fun onSchedule(context: Context, map: DataMap?) {
        val minutes = map?.getIntegerArrayList(AlarmScheduleContract.KEY_MINUTES).orEmpty()
        prefs(context).edit().putString(KEY_MINUTES, minutes.joinToString(",")).commit()
        arm(context)
    }

    /**
     * Reads the schedule the phone last put, instead of waiting for a change.
     * A DataItem raises its event once: one that arrived while an older watch
     * build (which ignored it) was installed would otherwise never be seen.
     */
    suspend fun pull(context: Context) {
        runCatching {
            val items = Wearable.getDataClient(context)
                .getDataItems(Uri.parse("wear://*" + AlarmScheduleContract.PATH)).await()
            try {
                items.firstOrNull()?.let { onSchedule(context, DataMapItem.fromDataItem(it).dataMap) }
            } finally {
                items.release()
            }
        }.onFailure { Log.w(TAG, "could not read the alarm schedule", it) }
    }

    /** The phone said it is ringing: the backup for this slot stands down. */
    fun onPhoneRang(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_LAST_PHONE_RING, at).commit()
    }

    private fun minutes(context: Context): List<Int> =
        prefs(context).getString(KEY_MINUTES, "").orEmpty()
            .split(',').mapNotNull { it.trim().toIntOrNull() }

    /** Arms the check for the soonest locked time, or cancels it when there is none. */
    fun arm(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = nextSlotAt(minutes(context), ZonedDateTime.now())
        val pending = { slotAt: Long ->
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, Receiver::class.java).setAction(ACTION_CHECK).putExtra(EXTRA_SLOT_AT, slotAt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        if (next == null) {
            alarms.cancel(pending(0L))
            return
        }
        runCatching {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(next + GRACE_MILLIS, show), pending(next))
        }.onFailure { Log.w(TAG, "could not arm the backup check", it) }
    }

    private fun check(context: Context, slotAt: Long) {
        val lastRing = prefs(context).getLong(KEY_LAST_PHONE_RING, 0L)
        if (!phoneMissedSlot(slotAt, lastRing)) return
        Log.i(TAG, "phone did not ring for $slotAt; ringing on the watch")
        val text = "Your phone did not ring. Get up and get to it."
        post(context, text)
        Buzzer.play(context, phoneAlarmBuzzPattern())
        PhoneAlarmRingService.start(context, text)
    }

    private fun post(context: Context, text: String) {
        // Inlined grant check: lint only credits the guard in the same function.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val built = PhoneAlarmMirror.notification(context, text) ?: return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, built)
    }

    /** Fires [GRACE_MILLIS] after a locked time; also re-arms after boot and updates. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CHECK) {
                check(context, intent.getLongExtra(EXTRA_SLOT_AT, 0L))
            }
            arm(context)
        }
    }
}

/**
 * Pure: the soonest locked time strictly after [now] minus the grace, so a
 * check still due for a slot that has just passed is not skipped.
 */
fun nextSlotAt(minutes: List<Int>, now: ZonedDateTime): Long? {
    if (minutes.isEmpty()) return null
    val zone: ZoneId = now.zone
    val cutoff = now.toInstant().toEpochMilli() - BackupAlarm.GRACE_MILLIS
    return (0L..1L).flatMap { offset ->
        val day: LocalDate = now.toLocalDate().plusDays(offset)
        minutes.filter { it in 0 until 24 * 60 }.map { m ->
            day.atTime(LocalTime.of(m / 60, m % 60)).atZone(zone).toInstant().toEpochMilli()
        }
    }.filter { it > cutoff }.minOrNull()
}

/**
 * Pure: the phone missed [slotAt] — no ring reported from a minute before it
 * on. A ring a minute early still counts, for a phone clock a little ahead.
 */
fun phoneMissedSlot(slotAt: Long, lastPhoneRingAt: Long): Boolean =
    slotAt > 0L && lastPhoneRingAt < slotAt - 60_000L
