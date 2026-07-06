package com.ogenrwotdaniel.wakeproof.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of everything the 5 AM alarm needs to actually fire and show. */
data class ReliabilityStatus(
    val notifications: Boolean = false,
    val camera: Boolean = false,
    val fullScreenIntent: Boolean = false,
    val exactAlarm: Boolean = false,
    val batteryUnrestricted: Boolean = false
) {
    /** Battery is strongly recommended but not blocking; the rest are. */
    val criticalOk: Boolean get() = notifications && camera && fullScreenIntent && exactAlarm

    val allOk: Boolean get() = criticalOk && batteryUnrestricted
}

/**
 * Re-checkable permission/capability probe used by onboarding and the
 * Settings reliability check.
 */
@Singleton
class ReliabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun check(): ReliabilityStatus = ReliabilityStatus(
        notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
        camera = granted(Manifest.permission.CAMERA),
        fullScreenIntent = context.getSystemService(NotificationManager::class.java)
            .canUseFullScreenIntent(),
        exactAlarm = context.getSystemService(AlarmManager::class.java)
            .canScheduleExactAlarms(),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    )

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
