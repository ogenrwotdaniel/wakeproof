package com.ogenrwotdaniel.wakeproof.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.ogenrwotdaniel.wakeproof.distraction.AppBlockService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-registers all enabled alarms after a reboot (AlarmManager state dies with
 * it). Runs directBootAware: on LOCKED_BOOT_COMPLETED — i.e. an unattended
 * reboot that is still sitting at the lock screen — Room is unreadable, so the
 * schedule is restored from [DirectBootAlarmStore] instead. Once unlocked it
 * also restarts app-limit enforcement ([AppBlockService]).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmInitializer: AlarmInitializer
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var directBootStore: DirectBootAlarmStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            directBootStore.all().forEach(scheduler::schedule)
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                alarmInitializer.initialize()
                // App blocking used to wait for the next 15-minute worker run
                // after a reboot, leaving capped apps open in the meantime.
                runCatching { AppBlockService.sync(context.applicationContext) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
