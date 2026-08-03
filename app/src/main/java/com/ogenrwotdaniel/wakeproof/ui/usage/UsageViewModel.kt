package com.ogenrwotdaniel.wakeproof.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CachedReport
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import android.content.Context
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import com.ogenrwotdaniel.wakeproof.data.TodayStatus
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.UsageSnapshot
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import com.ogenrwotdaniel.wakeproof.data.WeekStats
import com.ogenrwotdaniel.wakeproof.data.WeeklyReportGenerator
import com.ogenrwotdaniel.wakeproof.data.WeeklyReportRepository
import com.ogenrwotdaniel.wakeproof.data.db.SubjectMinutes
import com.ogenrwotdaniel.wakeproof.distraction.AppBlockService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the phone-usage screen; [refresh] re-checks the special-access grant. */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val usageStatsRepository: UsageStatsRepository,
    private val limitRepository: DistractionLimitRepository,
    private val reportGenerator: WeeklyReportGenerator,
    private val reportRepository: WeeklyReportRepository,
    private val settingsRepository: SettingsRepository,
    statsRepository: StatsRepository,
    completionRepository: CompletionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** Last cached weekly report (may be from a previous week). */
    val report: StateFlow<CachedReport?> = reportRepository.report
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Per-day focus/pushup/water for the last 7 days, oldest first. */
    private val weekDays = statsRepository.observeWeek()

    data class ReportUiState(val generating: Boolean = false)

    private val _reportUi = MutableStateFlow(ReportUiState())
    val reportUi: StateFlow<ReportUiState> = _reportUi.asStateFlow()

    /**
     * Aggregates the week and writes the report on-device. Nothing leaves the
     * phone and nothing can fail, so there is no error state to render — the
     * numbers are all in Room already.
     */
    fun generateReport() {
        if (_reportUi.value.generating) return
        _reportUi.value = ReportUiState(generating = true)
        viewModelScope.launch {
            reportRepository.save(reportGenerator.report(buildWeekStats()))
            _reportUi.value = ReportUiState()
        }
    }

    private suspend fun buildWeekStats(): WeekStats = WeekStats(
        days = weekDays.first(),
        focusBySubject = subjectMinutes.value,
        screenByDay = _snapshot.value?.week.orEmpty(),
        topApps = _snapshot.value?.topApps.orEmpty(),
        dailyGoalMinutes = settingsRepository.settings.first().dailyFocusGoalMinutes
    )

    /** Package → daily distraction cap. */
    val limits: StateFlow<Map<String, AppLimit>> = limitRepository.limits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** One capped app as the limits list shows it. */
    data class LimitedApp(
        val packageName: String,
        val label: String,
        val limit: AppLimit,
        val usedMinutes: Long
    )

    /** Today's measured minutes for capped apps, refreshed on each resume. */
    private val _limitUsage = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Every capped app, whether or not it made today's top-apps list. Without
     * this a limit on something barely used today is invisible, which reads as
     * "the limit didn't save".
     */
    val limitedApps: StateFlow<List<LimitedApp>> =
        combine(limitRepository.limits, _limitUsage) { limits, usage ->
            limits.map { (pkg, limit) ->
                LimitedApp(
                    packageName = pkg,
                    label = usageStatsRepository.appLabel(pkg),
                    limit = limit,
                    usedMinutes = usage[pkg] ?: 0L
                )
            }.sortedBy { it.label.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Full app list for the picker; loaded on first use, then cached. */
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch { _installedApps.value = usageStatsRepository.installedApps() }
    }

    /** True once the grants a hard block needs are both in place. */
    fun canEnforceBlocks(): Boolean =
        usageStatsRepository.hasAccess() && android.provider.Settings.canDrawOverlays(context)

    fun setLimit(packageName: String, minutes: Int?, mode: LimitMode = LimitMode.NUDGE) {
        viewModelScope.launch {
            limitRepository.setLimit(packageName, minutes, mode)
            // Picking up (or dropping) the last blocked app starts/stops the guard.
            AppBlockService.sync(context)
        }
    }

    /** Focus minutes per subject over the last 7 days. */
    val subjectMinutes: StateFlow<List<SubjectMinutes>> = completionRepository
        .observeFocusBySubject(LocalDate.now().minusDays(6).toEpochDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _hasAccess = MutableStateFlow(usageStatsRepository.hasAccess())
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val _snapshot = MutableStateFlow<UsageSnapshot?>(null)
    val snapshot: StateFlow<UsageSnapshot?> = _snapshot.asStateFlow()

    val today: StateFlow<TodayStatus> = statsRepository.observeToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayStatus())

    /** Called on every resume: the user may have just granted usage access. */
    fun refresh() {
        _hasAccess.value = usageStatsRepository.hasAccess()
        if (!_hasAccess.value) return
        viewModelScope.launch {
            _snapshot.value = usageStatsRepository.snapshot()
            // Same event-based measurement the enforcer uses, so the number shown
            // next to a cap is the number the block actually acts on.
            val capped = limitRepository.currentLimits().keys
            _limitUsage.value = usageStatsRepository.todayForegroundMillisFor(capped)
                .mapValues { (_, millis) -> millis / 60_000 }
        }
    }
}
