package com.ogenrwotdaniel.wakeproof.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.alarm.AlarmScheduler
import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home dashboard state: the alarm list plus developer test-fire actions. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler
) : ViewModel() {

    val alarms: StateFlow<List<Alarm>> = repository.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
