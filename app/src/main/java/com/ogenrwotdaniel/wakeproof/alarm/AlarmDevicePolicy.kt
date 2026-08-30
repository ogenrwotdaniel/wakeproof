package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether *this* device is the one that rings alarms.
 *
 * Alarms sync, which is right — an alarm added on the tablet should reach the
 * phone, and the daily goals are derived from the same schedule everywhere (see
 * [com.ogenrwotdaniel.wakeproof.data.dailyGoalsFrom]). What must NOT sync is the
 * *ringing*: two devices waking the house at 05:00 for one set of pushups is
 * noise, and silencing one of them does nothing about the other.
 *
 * So the schedule is shared data and ringing is a per-device role. A companion
 * device holds every alarm, shows every alarm, can edit every alarm, and arms
 * none of them.
 *
 * ### Device-protected storage, deliberately
 *
 * Like [DirectBootAlarmStore] and [SnoozeStore], this lives outside Room. An
 * alarm can fire before the first unlock, when credential-encrypted storage —
 * and therefore DataStore — is unreadable, and that is exactly the moment a
 * companion device must be able to say "not mine". Reading the role has to work
 * in the same window the alarm does.
 */
@Singleton
class AlarmDevicePolicy @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("alarm_device_role", Context.MODE_PRIVATE)

    private val _ringsAlarms = MutableStateFlow(prefs.getBoolean(KEY_RINGS, defaultForThisDevice()))

    /** For the settings screen. */
    val ringsAlarmsFlow: StateFlow<Boolean> = _ringsAlarms.asStateFlow()

    /**
     * Synchronous because every caller is one: [AlarmScheduler] arms intents from
     * ordinary functions and [AlarmReceiver] decides whether to ring inside
     * `onReceive`, where suspending to read a preference is not an option.
     */
    val ringsAlarms: Boolean get() = _ringsAlarms.value

    fun setRingsAlarms(value: Boolean) {
        prefs.edit().putBoolean(KEY_RINGS, value).apply()
        _ringsAlarms.value = value
    }

    /**
     * A guess, not a decision — the setting is user-visible and overrides this.
     *
     * Telephony alone is not enough: a cellular tablet has it too, and the
     * Galaxy Tab S9 FE+ is exactly the device this whole file exists to keep
     * quiet. Pairing it with a phone-sized shortest edge is what separates the
     * two, and both together are wrong only for a phone with no SIM support at
     * all, which then simply needs the switch turning on once.
     */
    private fun defaultForThisDevice(): Boolean {
        val hasTelephony = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
        val phoneSized = context.resources.configuration.smallestScreenWidthDp < TABLET_SW_DP
        return hasTelephony && phoneSized
    }

    private companion object {
        const val KEY_RINGS = "rings_alarms"

        /** Android's own definition of "large screen" for resource qualifiers. */
        const val TABLET_SW_DP = 600
    }
}
