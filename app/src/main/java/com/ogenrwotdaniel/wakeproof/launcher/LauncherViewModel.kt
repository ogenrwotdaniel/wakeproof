package com.ogenrwotdaniel.wakeproof.launcher

import android.app.AlarmManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.data.AgendaRepository
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import com.ogenrwotdaniel.wakeproof.data.StreakInfo
import com.ogenrwotdaniel.wakeproof.data.TodayStatus
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.distraction.AppGate
import com.ogenrwotdaniel.wakeproof.distraction.WallRulesRepository
import com.ogenrwotdaniel.wakeproof.distraction.essentialPackages
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs the minimalist home screen. */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStatsRepository: UsageStatsRepository,
    private val settingsRepository: SettingsRepository,
    private val agendaRepository: AgendaRepository,
    private val wallRulesRepository: WallRulesRepository,
    limitRepository: DistractionLimitRepository,
    statsRepository: StatsRepository
) : ViewModel() {

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** Every launchable app. Order carries no meaning — the screen shuffles it. */
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _pinned = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** The permanent tools at the foot of the home screen; see [permanentPackages]. */
    val pinned: StateFlow<List<InstalledApp>> = _pinned.asStateFlow()

    private val _gated = MutableStateFlow<Set<String>>(emptySet())

    /** Apps that open only through the five-minute wait ([AppGate]). */
    val gated: StateFlow<Set<String>> = _gated.asStateFlow()

    private val _agenda = MutableStateFlow<List<AgendaEvent>>(emptyList())

    /** Today's calendar events ([onAgenda]), for the home-screen agenda. */
    val agenda: StateFlow<List<AgendaEvent>> = _agenda.asStateFlow()

    val doneEventKeys: StateFlow<Set<String>> = agendaRepository.doneKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun hasCalendarPermission(): Boolean = agendaRepository.hasPermission()

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
        viewModelScope.launch {
            val apps = usageStatsRepository.installedApps()
            val essentials = withContext(Dispatchers.IO) { essentialPackages(context) }
            val gateBrowsers = wallRulesRepository.current().gateBrowsers
            _gated.value = withContext(Dispatchers.IO) {
                val browsers = AppGate.browserPackages(context)
                apps.filter {
                    AppGate.isGated(context, it.packageName, essentials, browsers, gateBrowsers)
                }
                    .map { it.packageName }.toSet()
            }
            _pinned.value = pinnedApps(context, apps)
            _apps.value = apps
            _agenda.value = withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                agendaRepository.upcomingEvents().filter { onAgenda(it, today) }
            }
        }
    }

    /** Minutes spent in [packageName] today, for the mindful pause. */
    suspend fun usedMinutesToday(packageName: String): Long =
        (usageStatsRepository.todayForegroundMillisFor(setOf(packageName))[packageName] ?: 0L) / 60_000

    /** The next alarm the system knows of — WakeProof's or any other clock app's. */
    fun nextAlarmMillis(): Long? =
        context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    /**
     * Whether [packageName] may be pinned. Social, video and messaging apps and
     * capped apps never can: a pin is a shortcut, and those are exactly the apps
     * that should take the long way round. Nor can the permanent tools, which
     * are already there.
     */
    fun canPin(packageName: String): Boolean =
        packageName !in _gated.value && packageName !in limits.value &&
            _pinned.value.none { it.packageName == packageName }

    fun toggleFavorite(packageName: String) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().launcherFavorites
            settingsRepository.setLauncherFavorites(
                when {
                    packageName in current -> current - packageName
                    canPin(packageName) -> (current + packageName).takeLast(MAX_FAVORITES)
                    else -> current
                }
            )
        }
    }

    fun setEventDone(event: AgendaEvent, done: Boolean) {
        viewModelScope.launch {
            agendaRepository.setDone(event, done, _agenda.value.map { it.key }.toSet())
        }
    }

    companion object {
        /** A home screen of twenty favourites is an app drawer with extra steps. */
        const val MAX_FAVORITES = 8

        /**
         * Whether [event] belongs on the home screen's agenda: today only. Was
         * today plus the next two days, which on a real calendar ran to "+ 18
         * more" — a home screen showing the week is a planner, not a glance at
         * what today asks of you.
         *
         * All-day events must fall exactly on today. The provider stores them
         * as UTC midnights, so east of Greenwich yesterday's all-day event
         * still overlaps the start of the local day and comes back from the
         * query; timed events that began before today and run into it stay.
         */
        fun onAgenda(event: AgendaEvent, today: LocalDate): Boolean =
            if (event.allDay) eventDay(event) == today else eventDay(event) <= today
    }
}
