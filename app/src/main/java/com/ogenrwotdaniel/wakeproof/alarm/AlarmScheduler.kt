package com.ogenrwotdaniel.wakeproof.alarm

import android.annotation.SuppressLint
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
 *
 * Lint's MissingPermission check only credits SCHEDULE_EXACT_ALARM; the
 * declared USE_EXACT_ALARM covers setAlarmClock for genuine alarm apps.
 */
@SuppressLint("MissingPermission")
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directBootStore: DirectBootAlarmStore,
    private val devicePolicy: AlarmDevicePolicy
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * (Re)schedules the next occurrence of [alarm] on a day its
     * [Alarm.daysMask] allows.
     *
     * On a companion device this cancels instead of arming. Every path that
     * arms an alarm funnels through here — startup, sync, the settings screen,
     * a snooze, the receiver rescheduling tomorrow — so one check here is what
     * makes "alarms ring on the phone only" true rather than mostly true.
     */
    fun schedule(alarm: Alarm) {
        if (!devicePolicy.ringsAlarms) {
            cancelById(alarm.id)
            return
        }
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(
                nextTriggerAtMillis(alarm.hour, alarm.minute, alarm.daysMask),
                showAlarmsIntent()
            ),
            fireIntent(alarm.id, requestCode = alarm.id.toInt())
        )
        directBootStore.put(alarm)
    }

    fun cancel(alarm: Alarm) = cancelById(alarm.id)

    /**
     * Drops every schedule belonging to [alarmId] — daily, snoozed and test —
     * and forgets it in the mirror.
     *
     * Takes an id rather than an [Alarm] because the callers that need it most
     * have no row to pass: an alarm hard-deleted from the table still has its
     * pending intents armed, and by then the id is all that is left of it.
     */
    fun cancelById(alarmId: Long) {
        val requestCode = alarmId.toInt()
        alarmManager.cancel(fireIntent(alarmId, requestCode))
        alarmManager.cancel(fireIntent(alarmId, requestCode + SNOOZE_REQUEST_CODE_OFFSET))
        alarmManager.cancel(
            fireIntent(alarmId, requestCode + TEST_REQUEST_CODE_OFFSET, testFire = true)
        )
        directBootStore.remove(alarmId)
    }

    /**
     * Rings [alarmId] again in [minutes], without touching its daily schedule —
     * a distinct request code keeps the two apart, so a snooze taken at 05:03
     * cannot displace tomorrow's 05:00.
     */
    fun scheduleSnooze(alarmId: Long, minutes: Int) {
        if (!devicePolicy.ringsAlarms) return
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(
                System.currentTimeMillis() + minutes * 60_000L,
                showAlarmsIntent()
            ),
            fireIntent(alarmId, requestCode = alarmId.toInt() + SNOOZE_REQUEST_CODE_OFFSET)
        )
    }

    /**
     * Fires [alarm] in [delaySeconds] seconds through the full real pipeline
     * (AlarmManager → receiver → service → full-screen intent) without touching
     * its daily schedule — a distinct request code keeps the PendingIntents apart.
     */
    fun scheduleTestFire(alarm: Alarm, delaySeconds: Int) {
        if (!devicePolicy.ringsAlarms) return
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

    private fun nextTriggerAtMillis(hour: Int, minute: Int, daysMask: Int): Long =
        nextAlarmTrigger(LocalDateTime.now(), hour, minute, daysMask)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private companion object {
        const val TEST_REQUEST_CODE_OFFSET = 10_000
        const val SNOOZE_REQUEST_CODE_OFFSET = 20_000
    }
}
