package com.ogenrwotdaniel.wakeproof.agenda

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme

/**
 * The event-focus confirm on its own, for the reminder notification's
 * "Focus for this event" (2026-11-01). Translucent, in its own task, gone
 * from Recents: it is a question, not a place. The length is worked out when
 * it opens, not when the reminder was posted, so a reminder answered late
 * still ends the session with the event.
 */
class EventFocusActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val event = AgendaEvent(
            key = intent.getStringExtra(EXTRA_KEY).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            beginMillis = intent.getLongExtra(EXTRA_BEGIN, 0L),
            endMillis = intent.getLongExtra(EXTRA_END, 0L),
            allDay = false,
            calendarName = ""
        )
        EventReminders.cancelNotification(this, event.key)
        val minutes = eventFocusMinutes(event, System.currentTimeMillis())
        val refusal = when {
            event.key.isEmpty() -> "That event is no longer on the calendar."
            PomodoroTimerService.state.value.active -> "A focus session is already running."
            minutes == null -> "Too little of \"${event.title}\" is left to focus for."
            else -> null
        }
        if (refusal != null || minutes == null) {
            Toast.makeText(this, refusal, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            WakeProofTheme {
                EventFocusDialog(
                    title = event.title,
                    minutes = minutes,
                    onConfirm = {
                        // Re-measured at the tap: the dialog may have sat open,
                        // and the session must still end with the event.
                        val now = eventFocusMinutes(event, System.currentTimeMillis())
                        if (now == null) {
                            Toast.makeText(this, "Too little of it is left to focus for.", Toast.LENGTH_LONG).show()
                        } else {
                            PomodoroTimerService.startForEvent(this, now, event.key, event.title)
                        }
                        finish()
                    },
                    onDismiss = ::finish
                )
            }
        }
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BEGIN = "begin"
        private const val EXTRA_END = "end"

        fun intent(context: Context, event: AgendaEvent): Intent =
            Intent(context, EventFocusActivity::class.java)
                .putExtra(EXTRA_KEY, event.key)
                .putExtra(EXTRA_TITLE, event.title)
                .putExtra(EXTRA_BEGIN, event.beginMillis)
                .putExtra(EXTRA_END, event.endMillis)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
