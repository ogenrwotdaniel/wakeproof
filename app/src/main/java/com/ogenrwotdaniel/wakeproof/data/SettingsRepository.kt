package com.ogenrwotdaniel.wakeproof.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** User-tunable settings (DataStore-backed). */
data class WakeProofSettings(
    val pushupTarget: Int = 15,
    val waterAmountMl: Int = 500,
    val emergencyHoldSeconds: Int = 60,
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val focusMusicEnabled: Boolean = true,
    val focusLockPhone: Boolean = true,
    val onboardingComplete: Boolean = false
)

/** Reads/writes [WakeProofSettings]. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.settingsDataStore

    val settings: Flow<WakeProofSettings> = dataStore.data.map { prefs ->
        WakeProofSettings(
            pushupTarget = prefs[PUSHUP_TARGET] ?: 15,
            waterAmountMl = prefs[WATER_AMOUNT_ML] ?: 500,
            emergencyHoldSeconds = prefs[EMERGENCY_HOLD_SECONDS] ?: 60,
            focusMinutes = prefs[FOCUS_MINUTES] ?: 25,
            shortBreakMinutes = prefs[SHORT_BREAK_MINUTES] ?: 5,
            longBreakMinutes = prefs[LONG_BREAK_MINUTES] ?: 15,
            focusMusicEnabled = prefs[FOCUS_MUSIC_ENABLED] ?: true,
            focusLockPhone = prefs[FOCUS_LOCK_PHONE] ?: true,
            onboardingComplete = prefs[ONBOARDING_COMPLETE] ?: false
        )
    }

    suspend fun setPushupTarget(value: Int) = dataStore.edit { it[PUSHUP_TARGET] = value }
    suspend fun setWaterAmountMl(value: Int) = dataStore.edit { it[WATER_AMOUNT_ML] = value }
    suspend fun setEmergencyHoldSeconds(value: Int) = dataStore.edit { it[EMERGENCY_HOLD_SECONDS] = value }
    suspend fun setFocusMinutes(value: Int) = dataStore.edit { it[FOCUS_MINUTES] = value }
    suspend fun setShortBreakMinutes(value: Int) = dataStore.edit { it[SHORT_BREAK_MINUTES] = value }
    suspend fun setLongBreakMinutes(value: Int) = dataStore.edit { it[LONG_BREAK_MINUTES] = value }
    suspend fun setFocusMusicEnabled(value: Boolean) = dataStore.edit { it[FOCUS_MUSIC_ENABLED] = value }
    suspend fun setFocusLockPhone(value: Boolean) = dataStore.edit { it[FOCUS_LOCK_PHONE] = value }
    suspend fun setOnboardingComplete(value: Boolean) = dataStore.edit { it[ONBOARDING_COMPLETE] = value }

    private companion object {
        val PUSHUP_TARGET = intPreferencesKey("pushup_target")
        val WATER_AMOUNT_ML = intPreferencesKey("water_amount_ml")
        val EMERGENCY_HOLD_SECONDS = intPreferencesKey("emergency_hold_seconds")
        val FOCUS_MINUTES = intPreferencesKey("focus_minutes")
        val SHORT_BREAK_MINUTES = intPreferencesKey("short_break_minutes")
        val LONG_BREAK_MINUTES = intPreferencesKey("long_break_minutes")
        val FOCUS_MUSIC_ENABLED = booleanPreferencesKey("focus_music_enabled")
        val FOCUS_LOCK_PHONE = booleanPreferencesKey("focus_lock_phone")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
