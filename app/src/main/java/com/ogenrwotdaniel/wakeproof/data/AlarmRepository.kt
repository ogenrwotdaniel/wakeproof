package com.ogenrwotdaniel.wakeproof.data

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
class AlarmRepository @Inject constructor(private val dao: AlarmDao) {

    fun observeAlarms(): Flow<List<Alarm>> = dao.observeAll()

    fun observeAlarm(id: Long): Flow<Alarm?> = dao.observeById(id)

    suspend fun getAlarm(id: Long): Alarm? = dao.getById(id)

    suspend fun getEnabledAlarms(): List<Alarm> = dao.getEnabled()

    suspend fun firstAlarmOfType(type: AlarmType): Alarm? = dao.getFirstByType(type)

    suspend fun updateAlarm(alarm: Alarm) = dao.update(alarm)

    suspend fun markCompletedToday(id: Long) =
        dao.markCompleted(id, LocalDate.now().toEpochDay())

    suspend fun seedDefaultAlarmsIfEmpty() {
        if (dao.count() == 0) dao.insertAll(DEFAULT_ALARMS)
    }

    private companion object {
        val DEFAULT_ALARMS = listOf(
            Alarm(type = AlarmType.PUSHUP, hour = 5, minute = 0, label = "Morning pushups"),
            Alarm(type = AlarmType.WATER, hour = 8, minute = 50, label = "Morning water"),
            Alarm(type = AlarmType.WATER, hour = 12, minute = 50, label = "Midday water"),
            Alarm(type = AlarmType.WATER, hour = 18, minute = 30, label = "Evening water")
        )
    }
}
