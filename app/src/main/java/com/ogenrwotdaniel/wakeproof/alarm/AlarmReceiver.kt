package com.ogenrwotdaniel.wakeproof.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired by AlarmManager at alarm time. Starts the ringing foreground service
 * and immediately schedules tomorrow's occurrence (daily repeat).
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var directBootStore: DirectBootAlarmStore
    @Inject lateinit var devicePolicy: AlarmDevicePolicy

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId == -1L) return

        // A companion device never rings, even for an intent that survived the
        // role being switched off — a stale PendingIntent outlives the setting
        // that armed it. The role lives in device-protected storage precisely so
        // this check works here, before the first unlock, where Room does not.
        if (!devicePolicy.ringsAlarms) {
            scheduler.cancelById(alarmId)
            return
        }

        // Before the first unlock Room (credential-encrypted) is unreadable, so the
        // device-protected mirror is the only account of the schedule there is:
        // ring on its word, and reschedule from it. A test fire answers to nothing
        // but itself and never touches the daily schedule.
        val testFire = intent.getBooleanExtra(EXTRA_TEST_FIRE, false)
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        if (testFire || !unlocked) {
            AlarmRingingService.start(context, alarmId)
            if (testFire) return
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    directBootStore.get(alarmId)?.let(scheduler::schedule)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val alarm = runCatching { repository.getAlarm(alarmId) }
                    .getOrElse {
                        // Storage unreadable for some other reason. An alarm clock that
                        // is unsure must still wake the user, so ring and leave the
                        // schedule alone.
                        AlarmRingingService.start(context, alarmId)
                        return@launch
                    }
                if (alarm == null || !alarm.enabled) {
                    // Deleted, switched off, or a starter alarm retired by sync — yet
                    // AlarmManager still had this armed. Ringing it now would be a
                    // ghost: no row means no row to silence, so no screen in the app
                    // can turn it off. Drop the schedule instead of waking anyone.
                    scheduler.cancelById(alarmId)
                    return@launch
                }
                AlarmRingingService.start(context, alarmId)
                scheduler.schedule(alarm)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
