package com.ogenrwotdaniel.wakeproof.ui.alarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.alarm.AlarmScheduler
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
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

/** Exposes the currently ringing alarm, its snooze allowance, and its completion. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlarmRingingViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val snoozeStore: SnoozeStore
) : ViewModel() {

    private val alarmId = MutableStateFlow(-1L)

    private val _snoozesLeft = MutableStateFlow(SnoozeStore.MAX_PER_DAY)

    /** Snoozes this alarm has left today; 0 hides the button. */
    val snoozesLeft: StateFlow<Int> = _snoozesLeft

    val alarm: StateFlow<Alarm?> = alarmId
        // Room is unreadable before the device's first unlock; the screen
        // falls back to its generic text on null rather than crashing.
        .flatMapLatest { id -> repository.observeAlarm(id).catch { emit(null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAlarmId(id: Long) {
        alarmId.value = id
        _snoozesLeft.value = if (id == -1L) 0 else snoozeStore.remaining(id)
    }

    /**
     * Postpones this alarm by [SnoozeStore.SNOOZE_MINUTES] and spends one of the
     * day's snoozes, then runs [onSnoozed] so the caller can silence the ringtone
     * and close the screen.
     *
     * The allowance is the point: verification can be genuinely impossible for a
     * moment — no room to do pushups, a phone handed to someone else — and with no
     * way out at all, testers were rebooting the phone or uninstalling the app.
     * Three a day leaves the alarm hard to ignore without making it a trap.
     */
    fun snooze(onSnoozed: () -> Unit) {
        val id = alarmId.value
        if (id == -1L || _snoozesLeft.value <= 0) return
        _snoozesLeft.value = snoozeStore.record(id)
        scheduler.scheduleSnooze(id, SnoozeStore.SNOOZE_MINUTES)
        onSnoozed()
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
