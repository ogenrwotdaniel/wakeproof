package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Context
import android.content.SharedPreferences
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-protected mirror of the enabled alarm schedule. Room lives in
 * credential-encrypted storage, which is unavailable between a reboot and the
 * first unlock — exactly the window in which an unattended overnight reboot
 * must not lose the 5 AM alarm. [AlarmScheduler] keeps this mirror in sync and
 * [BootReceiver] reads it on LOCKED_BOOT_COMPLETED to re-register alarms
 * before the user has unlocked.
 */
@Singleton
class DirectBootAlarmStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("alarm_schedule_mirror", Context.MODE_PRIVATE)

    fun put(alarm: Alarm) {
        prefs.edit().putString(alarm.id.toString(), "${alarm.hour}:${alarm.minute}").apply()
    }

    fun remove(alarmId: Long) {
        prefs.edit().remove(alarmId.toString()).apply()
    }

    fun get(alarmId: Long): Alarm? =
        decode(alarmId.toString(), prefs.getString(alarmId.toString(), null))

    /** Schedule stubs for every mirrored alarm; only id/hour/minute are meaningful. */
    fun all(): List<Alarm> = prefs.all.mapNotNull { (key, value) -> decode(key, value as? String) }

    private fun decode(key: String, value: String?): Alarm? {
        val id = key.toLongOrNull() ?: return null
        val parts = value?.split(":") ?: return null
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return Alarm(id = id, type = AlarmType.CUSTOM, hour = hour, minute = minute)
    }
}
