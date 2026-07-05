package com.ogenrwotdaniel.wakeproof.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules alarms with [AlarmManager.setAlarmClock], the API reserved for
 * user-visible alarm clocks: exempt from Doze, shows the alarm icon in the
 * status bar, and needs no runtime grant thanks to USE_EXACT_ALARM.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directBootStore: DirectBootAlarmStore
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** (Re)schedules the next occurrence of [alarm]'s daily wall-clock time. */
    fun schedule(alarm: Alarm) {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(
                nextTriggerAtMillis(alarm.hour, alarm.minute),
                showAlarmsIntent()
            ),
            fireIntent(alarm.id, requestCode = alarm.id.toInt())
        )
        directBootStore.put(alarm)
    }

    fun cancel(alarm: Alarm) {
        alarmManager.cancel(fireIntent(alarm.id, requestCode = alarm.id.toInt()))
        directBootStore.remove(alarm.id)
    }

    /**
     * Fires [alarm] in [delaySeconds] seconds through the full real pipeline
     * (AlarmManager → receiver → service → full-screen intent) without touching
     * its daily schedule — a distinct request code keeps the PendingIntents apart.
     */
    fun scheduleTestFire(alarm: Alarm, delaySeconds: Int) {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(
                System.currentTimeMillis() + delaySeconds * 1000L,
                showAlarmsIntent()
            ),
            fireIntent(
                alarm.id,
                requestCode = alarm.id.toInt() + TEST_REQUEST_CODE_OFFSET,
                testFire = true
            )
        )
    }

    private fun fireIntent(alarmId: Long, requestCode: Int, testFire: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_TEST_FIRE, testFire),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun showAlarmsIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun nextTriggerAtMillis(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, minute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private companion object {
        const val TEST_REQUEST_CODE_OFFSET = 10_000
    }
}
