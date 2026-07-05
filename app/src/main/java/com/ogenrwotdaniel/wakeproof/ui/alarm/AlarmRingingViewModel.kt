package com.ogenrwotdaniel.wakeproof.ui.alarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Exposes the currently ringing alarm and records its completion. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlarmRingingViewModel @Inject constructor(
    private val repository: AlarmRepository
) : ViewModel() {

    private val alarmId = MutableStateFlow(-1L)

    val alarm: StateFlow<Alarm?> = alarmId
        // Room is unreadable before the device's first unlock; the screen
        // falls back to its generic text on null rather than crashing.
        .flatMapLatest { id -> repository.observeAlarm(id).catch { emit(null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAlarmId(id: Long) {
        alarmId.value = id
    }

    /** Marks today's goal done for this alarm, then runs [onDone] on the main thread. */
    fun markCompletedToday(onDone: () -> Unit) {
        viewModelScope.launch {
            alarmId.value.takeIf { it != -1L }?.let {
                // Never let a storage failure (e.g. pre-unlock) keep the alarm ringing.
                runCatching { repository.markCompletedToday(it) }
            }
            onDone()
        }
    }
}
