package com.ogenrwotdaniel.wakeproof.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ogenrwotdaniel.wakeproof.alarm.AlarmDevicePolicy
import com.ogenrwotdaniel.wakeproof.alarm.AlarmScheduler
import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import com.ogenrwotdaniel.wakeproof.data.StreakInfo
import com.ogenrwotdaniel.wakeproof.data.DayStat
import com.ogenrwotdaniel.wakeproof.data.TodayStatus
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.data.db.Exam
import com.ogenrwotdaniel.wakeproof.data.db.ExamDao
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home dashboard state: alarms, today's goals, streak, 7-day history. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    alarmDevicePolicy: AlarmDevicePolicy,
    private val examDao: ExamDao,
    statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /**
     * Claims the one review request this install gets, if [streakDays] has
     * earned it. True means the caller should show the sheet now; the flag is
     * set first, so a crash mid-sheet cannot make the app ask twice.
     */
    suspend fun claimReviewPrompt(streakDays: Int): Boolean {
        val asked = runCatching { settingsRepository.reviewPrompted() }.getOrDefault(true)
        if (!shouldAskForReview(streakDays, asked)) return false
        settingsRepository.markReviewPrompted()
        return true
    }

    /** Upcoming exams (today onward) for the countdown section. */
    val exams: StateFlow<List<Exam>> = examDao.observeUpcoming(LocalDate.now().toEpochDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addExam(name: String, epochDay: Long) {
        if (name.isBlank()) return
        viewModelScope.launch { examDao.insert(Exam(name = name.trim(), epochDay = epochDay)) }
    }

    fun deleteExam(exam: Exam) {
        viewModelScope.launch { examDao.softDelete(exam.id) }
    }

    /**
     * The shared Pomodoro timer, so Home can say a session is running.
     *
     * Home is where the app opens, and a running countdown was invisible from
     * it: the timer lives in a foreground service and survives leaving the
     * Pomodoro screen, so "am I still in a focus block?" was a question the
     * dashboard could not answer.
     */
    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state

    /** Daily focus goal in minutes, for the Focus card's progress. */
    val focusGoalMinutes: StateFlow<Int> = settingsRepository.settings
        .map { it.dailyFocusGoalMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 120)

    val alarms: StateFlow<List<Alarm>> = repository.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val today: StateFlow<TodayStatus> = statsRepository.observeToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayStatus())

    val week: StateFlow<List<DayStat>> = statsRepository.observeWeek()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streak: StateFlow<StreakInfo> = statsRepository.observeStreak()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreakInfo())

    /** Next enabled pushup alarm, in the device's clock format, or null when none. */
    /**
     * Whether this device is the one that rings.
     *
     * Home lists the alarms on every device, because the schedule is shared —
     * but on a companion device none of them will ever go off, and a list of
     * alarms that silently does nothing is indistinguishable from a broken
     * alarm clock. Saying so on the dashboard is the difference.
     */
    val ringsAlarms: StateFlow<Boolean> = alarmDevicePolicy.ringsAlarmsFlow

    val nextPushupAlarm: StateFlow<String?> = earliestAlarm(AlarmType.PUSHUP)
    val nextMathAlarm: StateFlow<String?> = earliestAlarm(AlarmType.MATH)

    private fun earliestAlarm(type: AlarmType): StateFlow<String?> = repository.observeAlarms()
        .map { list ->
            list.filter { it.type == type && it.enabled }
                .minByOrNull { it.hour * 60 + it.minute }
                ?.let { formatAlarmTime(context, it.hour, it.minute) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Fires the first alarm of [type] in [TEST_FIRE_DELAY_SECONDS] seconds through
     * the real AlarmManager pipeline — lock the phone to test the full-screen wake.
     */
    fun testFire(type: AlarmType, mission: AlarmType? = null) {
        viewModelScope.launch {
            repository.firstAlarmOfType(type)?.let {
                scheduler.scheduleTestFire(it, TEST_FIRE_DELAY_SECONDS, mission)
            }
        }
    }

    companion object {
        const val TEST_FIRE_DELAY_SECONDS = 10
    }
}
