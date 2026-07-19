package com.ogenrwotdaniel.wakeproof.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val examDao: ExamDao,
    statsRepository: StatsRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** Upcoming exams (today onward) for the countdown section. */
    val exams: StateFlow<List<Exam>> = examDao.observeUpcoming(LocalDate.now().toEpochDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addExam(name: String, epochDay: Long) {
        if (name.isBlank()) return
        viewModelScope.launch { examDao.insert(Exam(name = name.trim(), epochDay = epochDay)) }
    }

    fun deleteExam(exam: Exam) {
        viewModelScope.launch { examDao.delete(exam) }
    }

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

    /** Next enabled pushup alarm as "HH:MM", or null when none. */
    val nextPushupAlarm: StateFlow<String?> = repository.observeAlarms()
        .map { list ->
            list.filter { it.type == AlarmType.PUSHUP && it.enabled }
                .minByOrNull { it.hour * 60 + it.minute }
                ?.let { "%02d:%02d".format(it.hour, it.minute) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Fires the first alarm of [type] in [TEST_FIRE_DELAY_SECONDS] seconds through
     * the real AlarmManager pipeline — lock the phone to test the full-screen wake.
     */
    fun testFire(type: AlarmType) {
        viewModelScope.launch {
            repository.firstAlarmOfType(type)?.let {
                scheduler.scheduleTestFire(it, TEST_FIRE_DELAY_SECONDS)
            }
        }
    }

    companion object {
        const val TEST_FIRE_DELAY_SECONDS = 10
    }
}
