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
import com.google.android.gms.wearable.DataMap

/**
 * `/wakeproof/alarm` — the phone's ringing alarm, mirrored. A hand-kept copy
 * of the phone's `WearAlarmSync` constants, like the other Data Layer paths.
 */
internal object PhoneAlarmContract {
    const val PATH = "/wakeproof/alarm"
    const val KEY_RINGING = "a_ringing"
    const val KEY_STRICT = "a_strict"
    const val KEY_REPS = "a_reps"
    const val KEY_STARTED_AT = "a_started_at"
}

/**
 * Buzzes the wrist while an alarm rings on the phone (2026-11-04). Dismissing
 * is the phone's alone — pushups on its camera — so this offers no button that
 * silences the PHONE. The buzz itself stops when the phone says the ring
 * ended, when the watch is touched (like every other buzz, [Buzzer]), or after
 * [phoneAlarmBuzzPattern] runs out, so a phone that went out of range mid-ring
 * cannot leave the wrist buzzing for good.
 */
object PhoneAlarmMirror {
    private const val CHANNEL_ID = "wakeproof_phone_alarm"
    const val NOTIFICATION_ID = 61

    fun onChanged(context: Context, map: DataMap?) {
        val ringing = map?.getBoolean(PhoneAlarmContract.KEY_RINGING) == true
        val startedAt = map?.getLong(PhoneAlarmContract.KEY_STARTED_AT) ?: 0L
        if (!phoneAlarmBuzzes(ringing, startedAt, System.currentTimeMillis())) {
            stop(context)
            return
        }
        // The phone is ringing, so the backup alarm stands down for this slot.
        BackupAlarm.onPhoneRang(context, startedAt)
        val strict = map?.getBoolean(PhoneAlarmContract.KEY_STRICT) == true
        val reps = map?.getInt(PhoneAlarmContract.KEY_REPS) ?: 0
        val text = if (strict && reps > 0) "$reps pushups on your phone silence it." else "Verify on your phone to silence it."
        post(context, text)
        Buzzer.play(context, phoneAlarmBuzzPattern())
        // The same song as the phone, on the watch's speaker, when it is bundled.
        PhoneAlarmRingService.start(context, text)
    }

    private fun stop(context: Context) {
        PhoneAlarmRingService.stop(context)
        Buzzer.stop()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, text: String) {
        // Inlined grant check: lint only credits the guard in the same function.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val built = notification(context, text) ?: return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, built)
    }

    /** The "alarm on your phone" notification; also the song service's foreground one. */
    fun notification(context: Context, text: String): android.app.Notification? = runCatching {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An alarm ringing on your phone"
                // The buzz is the engine's own; the channel would double it.
                setSound(null, null)
                enableVibration(false)
            }
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Alarm on your phone")
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(open)
                .build()
    }.getOrNull()
}

/** A ring this old is not buzzed: the watch came back into range long after it. */
const val PHONE_ALARM_STALE_MILLIS = 30 * 60_000L

/** How long the wrist buzzes at most for one ring, if the phone never says it stopped. */
const val PHONE_ALARM_BUZZ_MILLIS = 10 * 60_000L

/** Pure: whether the wrist buzzes for this state of the phone's alarm. */
fun phoneAlarmBuzzes(ringing: Boolean, startedAt: Long, now: Long): Boolean =
    ringing && startedAt > 0L && now - startedAt in 0 until PHONE_ALARM_STALE_MILLIS

/**
 * Pure: 1 s pulses with 0.6 s gaps for [PHONE_ALARM_BUZZ_MILLIS] — a finite
 * waveform, so it ends on its own if the "stopped" never arrives.
 */
fun phoneAlarmBuzzPattern(): LongArray {
    val pulse = 1_000L
    val gap = 600L
    val pairs = (PHONE_ALARM_BUZZ_MILLIS / (pulse + gap)).toInt()
    return LongArray(pairs * 2) { i -> if (i % 2 == 0) gap else pulse }
}
