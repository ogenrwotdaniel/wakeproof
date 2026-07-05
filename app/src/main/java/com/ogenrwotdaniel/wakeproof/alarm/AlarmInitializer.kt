package com.ogenrwotdaniel.wakeproof.alarm

import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings AlarmManager in sync with the database: seeds the default alarms on
 * first launch and (re)schedules every enabled alarm. Safe to call repeatedly —
 * rescheduling replaces the existing PendingIntent. Called from app startup
 * and from [BootReceiver].
 */
@Singleton
class AlarmInitializer @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler
) {
    suspend fun initialize() {
        repository.seedDefaultAlarmsIfEmpty()
        repository.getEnabledAlarms().forEach(scheduler::schedule)
    }
}
