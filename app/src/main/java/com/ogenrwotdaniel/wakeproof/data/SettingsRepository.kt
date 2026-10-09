package com.ogenrwotdaniel.wakeproof.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** User-tunable settings (DataStore-backed). */
data class WakeProofSettings(
    val pushupTarget: Int = 15,
    /** Problems in a row a math alarm demands; see [com.ogenrwotdaniel.wakeproof.verify.math.MathChallenge]. */
    val mathProblems: Int = 3,
    /**
     * Ask "still up?" a few minutes after an alarm is verified, and ring again
     * if nobody answers. Proving you were awake for thirty seconds is not the
     * same as getting up, and falling back asleep is how most alarms lose.
     */
    val wakeUpCheck: Boolean = true,
    val waterAmountMl: Int = 500,
    /** Full capacity of the bottle you drink from; converts a level drop into ml. */
    val bottleCapacityMl: Int = 750,
    val emergencyHoldSeconds: Int = 60,
    /**
     * Read the water line on the device instead of sending the photos to a
     * vision model. On by default: it needs no API key, no network and no round
     * trip at 06:00, and it is what removes the honour-mode hold that used to
     * stand in for verification whenever no key was configured.
     *
     * The cloud is still consulted as a second opinion before any rejection is
     * shown, when a key exists. Turning this off forces the cloud path for
     * everything — the escape hatch if the on-device detector misreads your own
     * bottle, which has never been checked against a real photograph.
     */
    val offlineWaterVerification: Boolean = true,
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val focusMusicEnabled: Boolean = true,
    val breakMusicEnabled: Boolean = false,
    /**
     * Seconds the bundled study loop takes to ease up from silence when a phase
     * starts, and to ease back down before it ends. 0 = snap on and off; the
     * settings slider runs to 60.
     *
     * The fade-out is pre-rolled, so this is also how long before the phase
     * boundary the loop starts receding — a 60 s fade only makes sense on a
     * phase comfortably longer than a minute.
     *
     * Applies to the bundled loops and to the user's own music alike.
     */
    val soundFadeSeconds: Int = 5,
    /**
     * The single study-sound source: a [com.ogenrwotdaniel.wakeproof.pomodoro.FocusSound]
     * id, or `library` to play the user's own music from the phone instead.
     */
    val focusSoundId: String = "focus_ambient",
    /** Encoded [com.ogenrwotdaniel.wakeproof.music.MusicSelection]: what part of the library plays. */
    val musicSelection: String = "all",
    val musicShuffle: Boolean = true,
    /**
     * Pin the phone to WakeProof (screen pinning) while a session runs — focus
     * *and* break phases, released only by pause or stop. The break is part of
     * the session, so handing the phone back for it defeated the lock.
     */
    val focusLockPhone: Boolean = true,
    /**
     * While a focus countdown runs, the app shows the timer and nothing else:
     * no chat, no sound picker, no navigation, no way on to another screen
     * until the break. The phone-level pin ([focusLockPhone]) stops you leaving
     * the app; this stops you fiddling inside it, which is the distraction the
     * app itself was adding.
     */
    val focusImmersiveTimer: Boolean = true,
    val focusFloatingTimer: Boolean = true,
    /** Screen never times out while a session runs — focus *and* break phases. */
    val sessionKeepScreenOn: Boolean = true,
    val dailyFocusGoalMinutes: Int = 120,
    val studySubjects: Set<String> = setOf("General"),
    val activeSubject: String = "General",
    /**
     * Apps that ARE studying — Samsung Notes by default. Time in them during a
     * session counts as study, not as screen time against you, and leaving
     * WakeProof for one of them does not wither the session's tree.
     */
    val studyApps: Set<String> = DEFAULT_STUDY_APPS,
    /** A quiet line under the focus countdown, changing every block. */
    val focusQuotes: Boolean = true,
    /**
     * Forest-style stakes: leaving WakeProof mid-focus for anything that is not
     * a study app withers the tree growing for that block.
     */
    val deepFocus: Boolean = true,
    /** Packages pinned to the minimalist home screen, in order. */
    val launcherFavorites: List<String> = emptyList(),
    val onboardingComplete: Boolean = false
) {
    companion object {
        val DEFAULT_STUDY_APPS = setOf("com.samsung.android.app.notes")
    }
}

/** Reads/writes [WakeProofSettings]. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.settingsDataStore

    val settings: Flow<WakeProofSettings> = dataStore.data.map { prefs ->
        WakeProofSettings(
            pushupTarget = prefs[PUSHUP_TARGET] ?: 15,
            mathProblems = prefs[MATH_PROBLEMS] ?: 3,
            wakeUpCheck = prefs[WAKE_UP_CHECK] ?: true,
            waterAmountMl = prefs[WATER_AMOUNT_ML] ?: 500,
            bottleCapacityMl = prefs[BOTTLE_CAPACITY_ML] ?: 750,
            emergencyHoldSeconds = prefs[EMERGENCY_HOLD_SECONDS] ?: 60,
            offlineWaterVerification = prefs[OFFLINE_WATER_VERIFICATION] ?: true,
            focusMinutes = prefs[FOCUS_MINUTES] ?: 25,
            shortBreakMinutes = prefs[SHORT_BREAK_MINUTES] ?: 5,
            longBreakMinutes = prefs[LONG_BREAK_MINUTES] ?: 15,
            focusMusicEnabled = prefs[FOCUS_MUSIC_ENABLED] ?: true,
            breakMusicEnabled = prefs[BREAK_MUSIC_ENABLED] ?: false,
            soundFadeSeconds = prefs[SOUND_FADE_SECONDS] ?: 5,
            focusSoundId = prefs[FOCUS_SOUND_ID] ?: "focus_ambient",
            musicSelection = prefs[MUSIC_SELECTION] ?: "all",
            musicShuffle = prefs[MUSIC_SHUFFLE] ?: true,
            focusLockPhone = prefs[FOCUS_LOCK_PHONE] ?: true,
            focusImmersiveTimer = prefs[FOCUS_IMMERSIVE_TIMER] ?: true,
            focusFloatingTimer = prefs[FOCUS_FLOATING_TIMER] ?: true,
            sessionKeepScreenOn = prefs[SESSION_KEEP_SCREEN_ON] ?: true,
            dailyFocusGoalMinutes = prefs[DAILY_FOCUS_GOAL_MINUTES] ?: 120,
            studySubjects = prefs[STUDY_SUBJECTS]?.takeIf { it.isNotEmpty() } ?: setOf("General"),
            activeSubject = prefs[ACTIVE_SUBJECT] ?: "General",
            studyApps = prefs[STUDY_APPS] ?: WakeProofSettings.DEFAULT_STUDY_APPS,
            focusQuotes = prefs[FOCUS_QUOTES] ?: true,
            deepFocus = prefs[DEEP_FOCUS] ?: true,
            launcherFavorites = prefs[LAUNCHER_FAVORITES]
                ?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            onboardingComplete = prefs[ONBOARDING_COMPLETE] ?: false
        )
    }

    suspend fun setPushupTarget(value: Int) = dataStore.edit { it[PUSHUP_TARGET] = value }
    /** Whether the Play review sheet has been requested — once per install, ever. */
    suspend fun reviewPrompted(): Boolean = dataStore.data.first()[REVIEW_PROMPTED] ?: false
    suspend fun markReviewPrompted() = dataStore.edit { it[REVIEW_PROMPTED] = true }
    suspend fun setMathProblems(value: Int) = dataStore.edit { it[MATH_PROBLEMS] = value }
    suspend fun setWakeUpCheck(value: Boolean) = dataStore.edit { it[WAKE_UP_CHECK] = value }
    suspend fun setWaterAmountMl(value: Int) = dataStore.edit { it[WATER_AMOUNT_ML] = value }
    suspend fun setBottleCapacityMl(value: Int) = dataStore.edit { it[BOTTLE_CAPACITY_ML] = value }
    suspend fun setEmergencyHoldSeconds(value: Int) = dataStore.edit { it[EMERGENCY_HOLD_SECONDS] = value }
    suspend fun setOfflineWaterVerification(value: Boolean) =
        dataStore.edit { it[OFFLINE_WATER_VERIFICATION] = value }
    suspend fun setFocusMinutes(value: Int) = dataStore.edit { it[FOCUS_MINUTES] = value }
    suspend fun setShortBreakMinutes(value: Int) = dataStore.edit { it[SHORT_BREAK_MINUTES] = value }
    suspend fun setLongBreakMinutes(value: Int) = dataStore.edit { it[LONG_BREAK_MINUTES] = value }
    suspend fun setFocusMusicEnabled(value: Boolean) = dataStore.edit { it[FOCUS_MUSIC_ENABLED] = value }
    suspend fun setBreakMusicEnabled(value: Boolean) = dataStore.edit { it[BREAK_MUSIC_ENABLED] = value }
    suspend fun setSoundFadeSeconds(value: Int) = dataStore.edit { it[SOUND_FADE_SECONDS] = value }
    suspend fun setFocusSoundId(value: String) = dataStore.edit { it[FOCUS_SOUND_ID] = value }
    suspend fun setFocusLockPhone(value: Boolean) = dataStore.edit { it[FOCUS_LOCK_PHONE] = value }
    suspend fun setFocusImmersiveTimer(value: Boolean) =
        dataStore.edit { it[FOCUS_IMMERSIVE_TIMER] = value }
    suspend fun setFocusFloatingTimer(value: Boolean) = dataStore.edit { it[FOCUS_FLOATING_TIMER] = value }
    suspend fun setSessionKeepScreenOn(value: Boolean) =
        dataStore.edit { it[SESSION_KEEP_SCREEN_ON] = value }
    suspend fun setMusicSelection(value: String) = dataStore.edit { it[MUSIC_SELECTION] = value }
    suspend fun setMusicShuffle(value: Boolean) = dataStore.edit { it[MUSIC_SHUFFLE] = value }
    suspend fun setStudyApps(value: Set<String>) = dataStore.edit { it[STUDY_APPS] = value }
    suspend fun setFocusQuotes(value: Boolean) = dataStore.edit { it[FOCUS_QUOTES] = value }
    suspend fun setDeepFocus(value: Boolean) = dataStore.edit { it[DEEP_FOCUS] = value }
    suspend fun setLauncherFavorites(value: List<String>) =
        dataStore.edit { it[LAUNCHER_FAVORITES] = value.joinToString(",") }
    /**
     * A goal set on THIS device. Stamped with the time so cloud sync can tell it
     * apart from — and let it win over — an older value on another device.
     */
    suspend fun setDailyFocusGoalMinutes(value: Int) = dataStore.edit {
        it[DAILY_FOCUS_GOAL_MINUTES] = value
        it[DAILY_FOCUS_GOAL_UPDATED_AT] = System.currentTimeMillis()
    }

    /**
     * The daily focus goal and when it was last changed (0 = never changed on
     * this device since syncing it began). The one setting that syncs: the
     * streak is scored against it, so two devices with different goals showed
     * two different streaks for the same day.
     */
    suspend fun focusGoalStamp(): Pair<Int, Long> {
        val prefs = dataStore.data.first()
        return (prefs[DAILY_FOCUS_GOAL_MINUTES] ?: 120) to (prefs[DAILY_FOCUS_GOAL_UPDATED_AT] ?: 0L)
    }

    /** A goal downloaded from another device; keeps that device's timestamp. */
    suspend fun applySyncedFocusGoal(value: Int, updatedAt: Long) = dataStore.edit {
        it[DAILY_FOCUS_GOAL_MINUTES] = value
        it[DAILY_FOCUS_GOAL_UPDATED_AT] = updatedAt
    }

    /** Adding a subject also makes it the active one. */
    suspend fun addStudySubject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[STUDY_SUBJECTS] = (prefs[STUDY_SUBJECTS] ?: setOf("General")) + trimmed
            prefs[ACTIVE_SUBJECT] = trimmed
        }
    }

    suspend fun removeStudySubject(name: String) = dataStore.edit { prefs ->
        val remaining = ((prefs[STUDY_SUBJECTS] ?: setOf("General")) - name)
            .ifEmpty { setOf("General") }
        prefs[STUDY_SUBJECTS] = remaining
        if (prefs[ACTIVE_SUBJECT] !in remaining) prefs[ACTIVE_SUBJECT] = remaining.first()
    }

    suspend fun setActiveSubject(name: String) = dataStore.edit { it[ACTIVE_SUBJECT] = name }
    suspend fun setOnboardingComplete(value: Boolean) = dataStore.edit { it[ONBOARDING_COMPLETE] = value }

    private companion object {
        val PUSHUP_TARGET = intPreferencesKey("pushup_target")
        val MATH_PROBLEMS = intPreferencesKey("math_problems")
        val REVIEW_PROMPTED = booleanPreferencesKey("review_prompted")
        val WAKE_UP_CHECK = booleanPreferencesKey("wake_up_check")
        val WATER_AMOUNT_ML = intPreferencesKey("water_amount_ml")
        val BOTTLE_CAPACITY_ML = intPreferencesKey("bottle_capacity_ml")
        val EMERGENCY_HOLD_SECONDS = intPreferencesKey("emergency_hold_seconds")
        val OFFLINE_WATER_VERIFICATION = booleanPreferencesKey("offline_water_verification")
        val FOCUS_MINUTES = intPreferencesKey("focus_minutes")
        val SHORT_BREAK_MINUTES = intPreferencesKey("short_break_minutes")
        val LONG_BREAK_MINUTES = intPreferencesKey("long_break_minutes")
        val FOCUS_MUSIC_ENABLED = booleanPreferencesKey("focus_music_enabled")
        val BREAK_MUSIC_ENABLED = booleanPreferencesKey("break_music_enabled")
        val SOUND_FADE_SECONDS = intPreferencesKey("sound_fade_seconds")
        val FOCUS_IMMERSIVE_TIMER = booleanPreferencesKey("focus_immersive_timer")
        val FOCUS_SOUND_ID = stringPreferencesKey("focus_sound_id")
        val FOCUS_LOCK_PHONE = booleanPreferencesKey("focus_lock_phone")
        val FOCUS_FLOATING_TIMER = booleanPreferencesKey("focus_floating_timer")
        // Key name kept from when this only covered focus phases, so existing
        // installs keep their choice.
        val SESSION_KEEP_SCREEN_ON = booleanPreferencesKey("focus_keep_screen_on")
        val MUSIC_SELECTION = stringPreferencesKey("music_selection")
        val MUSIC_SHUFFLE = booleanPreferencesKey("music_shuffle")
        val STUDY_APPS = stringSetPreferencesKey("study_apps")
        val FOCUS_QUOTES = booleanPreferencesKey("focus_quotes")
        val DEEP_FOCUS = booleanPreferencesKey("deep_focus")
        val LAUNCHER_FAVORITES = stringPreferencesKey("launcher_favorites")
        val DAILY_FOCUS_GOAL_MINUTES = intPreferencesKey("daily_focus_goal_minutes")
        val DAILY_FOCUS_GOAL_UPDATED_AT = longPreferencesKey("daily_focus_goal_updated_at")
        val STUDY_SUBJECTS = stringSetPreferencesKey("study_subjects")
        val ACTIVE_SUBJECT = stringPreferencesKey("active_subject")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
