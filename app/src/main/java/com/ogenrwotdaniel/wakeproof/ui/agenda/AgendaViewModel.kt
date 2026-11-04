package com.ogenrwotdaniel.wakeproof.ui.agenda

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.agenda.startEventFocus
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.data.AgendaRepository
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.wear.WearAgendaSync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An agenda row: the calendar event plus its local tick-off state. */
data class AgendaItem(val event: AgendaEvent, val done: Boolean)

/**
 * Backs the agenda screen; [refresh] re-reads permission and events. Tapping a
 * title focuses for the event exactly as the home-screen agenda does
 * ([com.ogenrwotdaniel.wakeproof.agenda.eventFocusOffer] decides, [focusForEvent] starts).
 */
@HiltViewModel
class AgendaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agendaRepository: AgendaRepository
) : ViewModel() {

    /** The phone's own timer — a title tap only ticks while a session runs. */
    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state

    private val _hasPermission = MutableStateFlow(agendaRepository.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val events = MutableStateFlow<List<AgendaEvent>>(emptyList())

    val items: StateFlow<List<AgendaItem>> =
        combine(events, agendaRepository.doneKeys) { list, done ->
            list.map { AgendaItem(it, it.key in done) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Called on resume and after the permission dialog returns. */
    fun refresh() {
        _hasPermission.value = agendaRepository.hasPermission()
        if (!_hasPermission.value) return
        viewModelScope.launch {
            events.value = agendaRepository.upcomingEvents()
            WearAgendaSync.push(context, events.value)
        }
    }

    /** Starts a session for what is left of [event]; false when refused. */
    fun focusForEvent(event: AgendaEvent): Boolean = startEventFocus(context, event)

    fun setDone(item: AgendaItem, done: Boolean) {
        viewModelScope.launch {
            agendaRepository.setDone(item.event, done, events.value.map { it.key }.toSet())
        }
    }
}
