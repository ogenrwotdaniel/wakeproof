package com.ogenrwotdaniel.wakeproof.ui.usage

import com.ogenrwotdaniel.wakeproof.data.NightScreen
import kotlinx.coroutines.flow.map
import android.widget.Toast
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
import com.ogenrwotdaniel.wakeproof.data.DeviceStudyUsage
import com.ogenrwotdaniel.wakeproof.data.StudyTimeSummary
import com.ogenrwotdaniel.wakeproof.data.db.SubjectMinutes
import com.ogenrwotdaniel.wakeproof.data.studyTimeSummary
import com.ogenrwotdaniel.wakeproof.data.sync.StudyUsageSync
import com.ogenrwotdaniel.wakeproof.data.WallDay
import com.ogenrwotdaniel.wakeproof.distraction.AppBlockService
import com.ogenrwotdaniel.wakeproof.distraction.AppGate
import com.ogenrwotdaniel.wakeproof.distraction.WallRules
import com.ogenrwotdaniel.wakeproof.distraction.WallRulesRepository
import com.ogenrwotdaniel.wakeproof.distraction.WindowNotificationGuard
import com.ogenrwotdaniel.wakeproof.distraction.gatedAppsInOtherProfiles
import com.ogenrwotdaniel.wakeproof.pomodoro.SessionDnd
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Backs the phone-usage screen; [refresh] re-checks the special-access grant. */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val usageStatsRepository: UsageStatsRepository,
    private val limitRepository: DistractionLimitRepository,
    private val reportGenerator: WeeklyReportGenerator,
    private val reportRepository: WeeklyReportRepository,
    private val settingsRepository: SettingsRepository,
    private val studyUsageSync: StudyUsageSync,
    private val wallRulesRepository: WallRulesRepository,
    private val summaryStore: com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore,
    statsRepository: StatsRepository,
    completionRepository: CompletionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** Last cached weekly report (may be from a previous week). */
    val report: StateFlow<CachedReport?> = reportRepository.report
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Per-day focus and pushups for the last 7 days, oldest first. */
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

    private suspend fun buildWeekStats(): WeekStats {
        val days = weekDays.first()
        val epochRange = if (days.isNotEmpty()) days.first().epochDay..days.last().epochDay else LongRange.EMPTY
        val weekPhotos = runCatching {
            summaryStore.list().filter { it.epochDay in epochRange }
        }.getOrDefault(emptyList())
        val bySubject = weekPhotos.mapNotNull { it.subject }
            .groupingBy { it }
            .eachCount()
        return WeekStats(
            days = days,
            focusBySubject = subjectMinutes.value,
            screenByDay = _snapshot.value?.week.orEmpty(),
            topApps = _snapshot.value?.topApps.orEmpty(),
            dailyGoalMinutes = settingsRepository.settings.first().dailyFocusGoalMinutes,
            wall = _wallWeek.value,
            credits = settingsRepository.settings.first().subjectCredits,
            nights = _nights.value,
            summaryPages = weekPhotos.size,
            summaryPagesBySubject = bySubject
        )
    }

    private val _nights = MutableStateFlow<List<NightScreen>>(emptyList())

    /** Screen use inside the sleep wall, the last finished nights, oldest first. */
    val nights: StateFlow<List<NightScreen>> = _nights.asStateFlow()

    /** Credits per course, for the balance line under the subject breakdown. */
    val subjectCredits: StateFlow<Map<String, Int>> = settingsRepository.settings
        .map { it.subjectCredits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The walls over gated apps: fixed, shown for reading only. */
    val wallRules: StateFlow<WallRules> = wallRulesRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallRules())

    private val _wallWeek = MutableStateFlow<List<WallDay>>(emptyList())

    /** The last 7 days at the wall, oldest first. */
    val wallWeek: StateFlow<List<WallDay>> = _wallWeek.asStateFlow()

    private val _otherProfileApps = MutableStateFlow<List<String>>(emptyList())

    /** Waiting apps in Secure Folder / Dual Messenger, which nothing here can see. */
    val otherProfileApps: StateFlow<List<String>> = _otherProfileApps.asStateFlow()

    private val _dndAccess = MutableStateFlow(SessionDnd.hasAccess(context))
    val dndAccess: StateFlow<Boolean> = _dndAccess.asStateFlow()

    private val _notificationAccess = MutableStateFlow(WindowNotificationGuard.hasAccess(context))

    /** Whether windowed apps' notifications can be removed outside the open window. */
    val notificationAccess: StateFlow<Boolean> = _notificationAccess.asStateFlow()

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

    /** This device's measured study-app minutes today; refreshed on each resume. */
    private val _localStudy = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The account's other devices' reports for today (empty when not syncing). */
    private val _peerStudy = MutableStateFlow<List<DeviceStudyUsage>>(emptyList())

    /**
     * Today's study time: the study apps (Settings → Study apps) measured on
     * every synced device, plus any focus-timer time no screen measured,
     * credited to the app focus is spent in. Shown as study rather than lumped
     * into screen time — an evening in Samsung Notes is the opposite of the
     * thing the screen-time number warns about. WakeProof's own screens are
     * neither: a session's minutes come from the timer, whichever device ran it.
     */
    val studyTime: StateFlow<StudyTimeSummary?> = combine(
        _localStudy, _peerStudy, statsRepository.observeToday(), settingsRepository.settings
    ) { local, peers, today, settings ->
        studyTimeSummary(
            today = LocalDate.now().toEpochDay(),
            local = local,
            localLabels = local.keys.associateWith(usageStatsRepository::appLabel),
            peers = peers,
            focusMinutes = today.focusMinutes.toLong(),
            creditPackage = settings.focusCreditApp,
            ownPackage = context.packageName
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Label for the credited app, which may not be installed on this device. */
    fun appLabel(packageName: String): String = usageStatsRepository.appLabel(packageName)

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

    /** Stricter caps apply now; a looser one is refused, and the toast says so. */
    fun setLimit(packageName: String, minutes: Int?, mode: LimitMode = LimitMode.NUDGE) {
        viewModelScope.launch {
            if (!limitRepository.requestLimit(packageName, minutes, mode)) {
                Toast.makeText(context, "Limits can only be tightened.", Toast.LENGTH_LONG).show()
            }
            // Picking up the first blocked app starts the guard.
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

    /** Time spent in gated apps over each of the last 7 days, oldest first. */
    private suspend fun measureWallWeek(): List<WallDay> {
        val gateBrowsers = wallRulesRepository.current().gateBrowsers
        val gated = withContext(Dispatchers.IO) {
            AppGate.gatedPackages(context, usageStatsRepository.installedApps().map { it.packageName }, gateBrowsers)
        }
        val minutes = usageStatsRepository.weekMinutesForGroups(listOf(gated)).first()
            .associate { it.epochDay to it.minutes }
        val today = LocalDate.now().toEpochDay()
        val blackboard = com.ogenrwotdaniel.wakeproof.study.StudyBrowserTime.minutes(context, (today - 6)..today)
        return ((today - 6)..today).map { day ->
            WallDay(
                epochDay = day,
                waitingAppMinutes = minutes[day] ?: 0L,
                blackboardMinutes = blackboard[day] ?: 0L
            )
        }
    }

    /** Called on every resume: the user may have just granted usage access. */
    fun refresh() {
        _dndAccess.value = SessionDnd.hasAccess(context)
        _notificationAccess.value = WindowNotificationGuard.hasAccess(context)
        viewModelScope.launch {
            val gateBrowsers = wallRulesRepository.current().gateBrowsers
            _otherProfileApps.value = withContext(Dispatchers.IO) {
                gatedAppsInOtherProfiles(context, gateBrowsers)
            }
        }
        _hasAccess.value = usageStatsRepository.hasAccess()
        if (!_hasAccess.value) return
        viewModelScope.launch {
            _snapshot.value = usageStatsRepository.snapshot(settingsRepository.settings.first().studyApps)
            // Same event-based measurement the enforcer uses, so the number shown
            // next to a cap is the number the block actually acts on.
            val capped = limitRepository.currentLimits().keys
            _limitUsage.value = usageStatsRepository.todayForegroundMillisFor(capped)
                .mapValues { (_, millis) -> millis / 60_000 }
            _wallWeek.value = measureWallWeek()
            _nights.value = usageStatsRepository.nightScreens()
            val study = studyUsageSync.localMinutesToday()
            _localStudy.value = study
            // Publish ours before reading theirs, so a peer opening its card
            // right after sees this device's latest too.
            studyUsageSync.publish(study)
            _peerStudy.value = studyUsageSync.peersToday()
        }
    }
}
