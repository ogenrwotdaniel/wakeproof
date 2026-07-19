package com.ogenrwotdaniel.wakeproof.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.CachedReport
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import com.ogenrwotdaniel.wakeproof.data.TodayStatus
import com.ogenrwotdaniel.wakeproof.data.UsageSnapshot
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import com.ogenrwotdaniel.wakeproof.data.WeeklyReportRepository
import com.ogenrwotdaniel.wakeproof.data.db.SubjectMinutes
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the phone-usage screen; [refresh] re-checks the special-access grant. */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val usageStatsRepository: UsageStatsRepository,
    private val limitRepository: DistractionLimitRepository,
    private val kimiRepository: KimiRepository,
    private val reportRepository: WeeklyReportRepository,
    private val settingsRepository: SettingsRepository,
    statsRepository: StatsRepository,
    completionRepository: CompletionRepository,
    keyStore: ApiKeyStore
) : ViewModel() {

    val hasApiKey: Boolean = keyStore.hasKey()

    /** Last cached weekly report (may be from a previous week). */
    val report: StateFlow<CachedReport?> = reportRepository.report
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    data class ReportUiState(val generating: Boolean = false, val error: String? = null)

    private val _reportUi = MutableStateFlow(ReportUiState())
    val reportUi: StateFlow<ReportUiState> = _reportUi.asStateFlow()

    /** Aggregates the week and asks the AI coach for a write-up. */
    fun generateReport() {
        if (_reportUi.value.generating) return
        _reportUi.value = ReportUiState(generating = true)
        viewModelScope.launch {
            val summary = buildStatsSummary()
            _reportUi.value = when (val result = kimiRepository.weeklyReport(summary)) {
                is KimiResult.Success -> {
                    reportRepository.save(result.value)
                    ReportUiState()
                }
                is KimiResult.ApiError -> ReportUiState(error = "AI error: ${result.message}")
                KimiResult.NetworkError -> ReportUiState(error = "Network error — try again.")
                KimiResult.NoKey -> ReportUiState(error = "Add an API key in Settings first.")
            }
        }
    }

    /** Only aggregate numbers leave the device — no app names beyond the top 3. */
    private suspend fun buildStatsSummary(): String {
        val focus = subjectMinutes.value
            .joinToString { "${it.subject ?: "untagged"}: ${it.minutes} min" }
            .ifBlank { "none" }
        val screen = _snapshot.value?.week
            ?.joinToString {
                "${DayOfWeek.from(LocalDate.ofEpochDay(it.epochDay)).name.take(3)}: ${it.minutes}m"
            } ?: "unknown"
        val topApps = _snapshot.value?.topApps?.take(3)
            ?.joinToString { "${it.label} ${it.minutes}m" } ?: "unknown"
        val goal = settingsRepository.settings.first().dailyFocusGoalMinutes
        return "Focus minutes by subject (7 days): $focus.\n" +
            "Screen time per day (minutes): $screen.\n" +
            "Top apps today: $topApps.\n" +
            "Focus goal: $goal min/day."
    }

    /** Package → daily distraction cap in minutes. */
    val limits: StateFlow<Map<String, Int>> = limitRepository.limits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setLimit(packageName: String, minutes: Int?) {
        viewModelScope.launch { limitRepository.setLimit(packageName, minutes) }
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
        viewModelScope.launch { _snapshot.value = usageStatsRepository.snapshot() }
    }
}
