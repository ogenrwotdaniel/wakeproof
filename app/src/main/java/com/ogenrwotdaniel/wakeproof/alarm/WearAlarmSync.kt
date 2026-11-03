package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Tells the watch an alarm is ringing on this phone, and when it stopped
 * (2026-11-04, user's request: "when it rings on phone, I get it on the watch
 * too, but I dismiss it on the phone by pushups"). The watch buzzes at alarm
 * strength and shows what silences it; it has NO way to dismiss — an alarm you
 * can silence from your wrist is one you silence without getting up, which is
 * the exact thing WakeProof exists to stop (see the v1.3.0 notes).
 *
 * A DataItem, not a message: it is state, so a watch that comes back into
 * range picks up "ringing" or "stopped" as it is now rather than missing the
 * event. [KEY_STARTED_AT] makes every ring a new value, so a second ring the
 * same morning still raises a change on the watch. Fire-and-forget: the
 * ringing path must never wait on Bluetooth, and before the first unlock the
 * Data Layer may simply be unavailable.
 *
 * The contract is duplicated by hand in the watch's `PhoneAlarmContract`, like
 * the other Data Layer paths.
 */
object WearAlarmSync {
    const val PATH = "/wakeproof/alarm"
    const val KEY_RINGING = "a_ringing"
    const val KEY_STRICT = "a_strict"
    const val KEY_REPS = "a_reps"
    const val KEY_STARTED_AT = "a_started_at"

    const val SCHEDULE_PATH = "/wakeproof/alarm_schedule"
    const val KEY_SCHEDULE_MINUTES = "s_minutes"

    /**
     * The locked alarms' times, for the watch's backup alarm: it rings by
     * itself when the phone has not rung a couple of minutes after one. Put on
     * every reconcile; an unchanged DataItem raises nothing on the watch.
     */
    fun pushSchedule(context: Context, minutes: List<Int>) {
        runCatching {
            val request = PutDataMapRequest.create(SCHEDULE_PATH).apply {
                dataMap.putIntegerArrayList(KEY_SCHEDULE_MINUTES, ArrayList(minutes.sorted()))
            }.asPutDataRequest()
            Wearable.getDataClient(context.applicationContext).putDataItem(request)
        }
    }

    fun push(context: Context, ringing: Boolean, strict: Boolean) {
        runCatching {
            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.putBoolean(KEY_RINGING, ringing)
                dataMap.putBoolean(KEY_STRICT, strict)
                dataMap.putInt(KEY_REPS, StrictAlarm.REPS)
                dataMap.putLong(KEY_STARTED_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(request)
        }
    }
}
