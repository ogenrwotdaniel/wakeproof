package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.alarm.AlarmScheduler
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for alarms. Seeds the default WakeProof schedule
 * (pushups at 05:00, water at 08:50 / 12:50 / 18:30) on first launch.
 */
@Singleton
class AlarmRepository @Inject constructor(
    private val dao: AlarmDao,
    private val scheduler: AlarmScheduler
) {

    fun observeAlarms(): Flow<List<Alarm>> = dao.observeAll()

    fun observeAlarm(id: Long): Flow<Alarm?> = dao.observeById(id)

    suspend fun getAlarm(id: Long): Alarm? = dao.getById(id)

    suspend fun getEnabledAlarms(): List<Alarm> = dao.getEnabled()

    suspend fun firstAlarmOfType(type: AlarmType): Alarm? = dao.getFirstByType(type)

    /**
     * Re-stamps the row on the way out so the change is queued for upload. Doing
     * it here rather than at each call site means no screen can edit an alarm and
     * have it silently stay on this device.
     */
    suspend fun updateAlarm(alarm: Alarm) = dao.update(
        alarm.copy(
            updatedAt = System.currentTimeMillis(),
            pending = true,
            // An edited alarm is a deliberate one, so it stops being a starter
            // alarm and starts syncing.
            seeded = false
        )
    )

    suspend fun markCompletedToday(id: Long) =
        dao.markCompleted(id, LocalDate.now().toEpochDay())

    /**
     * Removes an alarm everywhere: cancels the pending AlarmManager intent (Room is
     * not the schedule) and leaves a tombstone so the other devices drop it too.
     */
    suspend fun deleteAlarm(alarm: Alarm) {
        scheduler.cancel(alarm)
        dao.softDelete(alarm.id)
    }

    suspend fun seedDefaultAlarmsIfEmpty() {
        if (dao.count() == 0) dao.insertAll(DEFAULT_ALARMS)
    }

    private companion object {
        /**
         * Marked `seeded` and *not* queued for upload: a device that seeds these
         * before the user has signed in anywhere must not push a starter schedule
         * into an account that already has a real one.
         */
        val DEFAULT_ALARMS = listOf(
            Alarm(type = AlarmType.PUSHUP, hour = 5, minute = 0, label = "Morning pushups"),
            Alarm(type = AlarmType.WATER, hour = 8, minute = 50, label = "Morning water"),
            Alarm(type = AlarmType.WATER, hour = 12, minute = 50, label = "Midday water"),
            Alarm(type = AlarmType.WATER, hour = 18, minute = 30, label = "Evening water")
        ).map { it.copy(seeded = true, pending = false) }
    }
}
