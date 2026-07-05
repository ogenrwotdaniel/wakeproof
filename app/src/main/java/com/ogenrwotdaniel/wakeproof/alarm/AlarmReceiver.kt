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

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId == -1L) return

        AlarmRingingService.start(context, alarmId)

        if (intent.getBooleanExtra(EXTRA_TEST_FIRE, false)) return
        // Before the first unlock Room (credential-encrypted) is unreadable;
        // fall back to the device-protected mirror for the daily reschedule.
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (unlocked) {
                    repository.getAlarm(alarmId)?.takeIf { it.enabled }?.let(scheduler::schedule)
                } else {
                    directBootStore.get(alarmId)?.let(scheduler::schedule)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
