package com.ogenrwotdaniel.wakeproof.launcher

import android.app.AlarmManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import com.ogenrwotdaniel.wakeproof.data.StreakInfo
import com.ogenrwotdaniel.wakeproof.data.TodayStatus
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the minimalist home screen. */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStatsRepository: UsageStatsRepository,
    private val settingsRepository: SettingsRepository,
    limitRepository: DistractionLimitRepository,
    statsRepository: StatsRepository
) : ViewModel() {

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** Every launchable app, alphabetical. */
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    val settings: StateFlow<WakeProofSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, WakeProofSettings())

    val limits: StateFlow<Map<String, AppLimit>> = limitRepository.limits
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val today: StateFlow<TodayStatus> = statsRepository.observeToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayStatus())

    val streak: StateFlow<StreakInfo> = statsRepository.observeStreak()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreakInfo())

    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state

    /**
     * Re-read on every resume rather than via a package broadcast receiver: the
     * home screen resumes every time it is looked at, which is exactly when a
     * freshly installed app has to be there.
     */
    fun refresh() {
        viewModelScope.launch { _apps.value = usageStatsRepository.installedApps() }
    }

    /** Minutes spent in [packageName] today, for the mindful pause. */
    suspend fun usedMinutesToday(packageName: String): Long =
        (usageStatsRepository.todayForegroundMillisFor(setOf(packageName))[packageName] ?: 0L) / 60_000

    /** The next alarm the system knows of — WakeProof's or any other clock app's. */
    fun nextAlarmMillis(): Long? =
        context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    fun toggleFavorite(packageName: String) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().launcherFavorites
            settingsRepository.setLauncherFavorites(
                if (packageName in current) current - packageName
                else (current + packageName).takeLast(MAX_FAVORITES)
            )
        }
    }

    companion object {
        /** A home screen of twenty favourites is an app drawer with extra steps. */
        const val MAX_FAVORITES = 8
    }
}
