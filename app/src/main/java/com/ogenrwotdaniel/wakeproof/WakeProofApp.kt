package com.ogenrwotdaniel.wakeproof

import android.app.Application
import android.os.UserManager
import com.ogenrwotdaniel.wakeproof.alarm.AlarmInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Hosts the Hilt dependency graph and brings the
 * alarm schedule in sync with the database on every process start.
 */
@HiltAndroidApp
class WakeProofApp : Application() {

    @Inject lateinit var alarmInitializer: AlarmInitializer

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Before the first unlock Room's storage is unreadable; BootReceiver
        // covers that window from the device-protected mirror instead.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            applicationScope.launch { alarmInitializer.initialize() }
        }
    }
}
