package com.ogenrwotdaniel.wakeproof.wear

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Today" on the watch (2026-11-04): the phone's timed calendar events for
 * today, pushed on `/wakeproof/agenda`.
 *
 * * ☐ ticks the event — on the PHONE ([TimerCommands.agendaDone]); the row
 *   changes only when the phone pushes the list back, never optimistically.
 * * Tapping a title asks "Focus until 15:00 (1 h 25 m)?" and starts the
 *   WATCH's own timer for exactly what is left of the event
 *   ([WatchTimerEngine.startForEvent]) — the same nuclear session as any
 *   other, walling the phone and tablet through [WatchSessionBeacon]. What
 *   cannot be focused for says WHY in the confirm area ([AgendaOffers.offer]:
 *   ticked, over, a session on this watch or on the phone, too little left),
 *   with "Tick it off" alongside — never a silent tick. A phone session counts:
 *   one session at a time across devices.
 * * Started with [EXTRA_EVENT_KEY] (a row on [AgendaTileService]) it goes
 *   straight to that event's confirm, or its refusal.
 *
 * A plain scrolling column of framework views, like [MainActivity]; the
 * ScrollView holds focus so the rotating bezel scrolls it.
 */
class AgendaActivity : ComponentActivity() {

    private val scope = MainScope()
    private lateinit var column: LinearLayout
    private lateinit var root: ScrollView
    /** A line above the list: "Phone unreachable", a refusal. Cleared by the next push. */
    private var status: String? = null
    /** The tile row's event, opened once on the first list read and then forgotten. */
    private var pendingEventKey: String? = null
    /** True while the confirm / refusal for one event is on screen, so a push does not yank it away. */
    private var inConfirm = false

    private val dataListener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
        val relevant = events.any { it.dataItem.uri.path == AgendaContract.PATH }
        events.release()
        if (relevant && !inConfirm) {
            status = null
            showList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Generous sides: a round face clips the corners of every row.
            setPadding(36, 36, 36, 48)
        }
        root = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            isFocusable = true
            isFocusableInTouchMode = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        pendingEventKey = intent.getStringExtra(EXTRA_EVENT_KEY)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_EVENT_KEY)?.let { pendingEventKey = it }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(dataListener)
        root.requestFocus()
        if (!inConfirm || pendingEventKey != null) showList()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(dataListener)
        super.onPause()
    }

    private fun showList() {
        inConfirm = false
        scope.launch {
            val agenda = WatchAgenda.read(applicationContext)
            val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
            val events = agenda.forDay(today)
            pendingEventKey?.let { key ->
                pendingEventKey = null
                val event = events.firstOrNull { it.key == key }
                if (event != null) {
                    onTitle(event)
                    return@launch
                }
                status = "That event is no longer on today's list."
            }
            column.removeAllViews()
            addText("Today", 18f, Color.WHITE)
            status?.let { addText(it, 13f, Color.LTGRAY) }
            when {
                !agenda.present -> addText("Nothing from the phone yet. Open WakeProof on the phone.", 14f, Color.GRAY)
                !agenda.allowed -> addText("Calendar access is off on the phone.", 14f, Color.GRAY)
                agenda.epochDay != today -> addText("Not synced today yet. Open WakeProof on the phone.", 14f, Color.GRAY)
                events.isEmpty() -> addText("Nothing timed on the calendar today.", 14f, Color.GRAY)
                else -> events.forEach { addRow(it) }
            }
            root.requestFocus()
        }
    }

    private fun addRow(event: WatchAgendaEvent) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tick = TextView(this).apply {
            text = if (event.done) "☑" else "☐"
            textSize = 20f
            setTextColor(if (event.done) Color.DKGRAY else Color.LTGRAY)
            setPadding(0, 8, 16, 8)
            setOnClickListener { if (!event.done) tick(event) }
        }
        val title = TextView(this).apply {
            text = "${timeFormat.format(Date(event.beginMillis))}  ${event.title}"
            textSize = 14f
            maxLines = 2
            setTextColor(if (event.done) Color.DKGRAY else Color.WHITE)
            if (event.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setPadding(0, 8, 0, 8)
            setOnClickListener { onTitle(event) }
        }
        row.addView(tick)
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 6 }
        )
    }

    private fun onTitle(event: WatchAgendaEvent) {
        inConfirm = true
        scope.launch {
            when (val offer = currentOffer(event)) {
                is AgendaOffer.Offer -> confirm(event, offer.minutes)
                is AgendaOffer.Refused -> refuse(event, offer.reason)
            }
        }
    }

    /** The offer as of now: the watch's timer and the phone's mirrored one. */
    private suspend fun currentOffer(event: WatchAgendaEvent): AgendaOffer = AgendaOffers.offer(
        event,
        WatchTimerEngine.state(applicationContext),
        TimerSnapshot.read(applicationContext),
        System.currentTimeMillis()
    )

    private fun refusalText(reason: AgendaRefusal) =
        AgendaOffers.refusalText(reason) { timeFormat.format(Date(it)) }

    /** Why [event] cannot be focused for, in the confirm area; ticking stays one tap away. */
    private fun refuse(event: WatchAgendaEvent, reason: AgendaRefusal) {
        inConfirm = true
        column.removeAllViews()
        addText("\"${event.title}\"", 14f, Color.LTGRAY)
        addText(refusalText(reason), 16f, Color.WHITE)
        if (!event.done) {
            addButton("Tick it off") {
                tick(event)
                showList()
            }
        }
        addButton("Back") { showList() }
        root.scrollTo(0, 0)
        root.requestFocus()
    }

    /** Asks the phone to tick [event]; the list changes when its push lands. */
    private fun tick(event: WatchAgendaEvent) {
        scope.launch {
            val delivered = TimerCommands.send(applicationContext, TimerCommands.agendaDone(event.key))
            if (!delivered) {
                status = "Phone unreachable. Couldn't tick it."
                showList()
            }
        }
    }

    private fun confirm(event: WatchAgendaEvent, minutes: Int) {
        inConfirm = true
        column.removeAllViews()
        val until = timeFormat.format(Date(event.endMillis))
        addText("Focus until $until (${WatchAgenda.lengthLabel(minutes)})?", 16f, Color.WHITE)
        addText("\"${event.title}\"\nOnce it starts it can't be paused or stopped.", 13f, Color.LTGRAY)
        addButton("Start") {
            // Re-checked at the tap: the confirm may have sat open while the
            // phone started a session or the event ran down.
            val again = currentOffer(event)
            if (again is AgendaOffer.Refused) {
                status = refusalText(again.reason)
                refuse(event, again.reason)
                return@addButton
            }
            if (WatchTimerEngine.startForEvent(applicationContext, event.beginMillis, event.endMillis)) {
                // Back to the main screen, which now shows the running block.
                // Started from the tile there is no MainActivity beneath, so
                // bring one up; CLEAR_TOP reuses it when there is.
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            } else {
                val reason = (currentOffer(event) as? AgendaOffer.Refused)?.reason
                status = reason?.let(::refusalText)
                    ?: if (WatchTimerEngine.summaryOwed(applicationContext)) SUMMARY_OWED_TEXT else "Couldn't start."
                if (reason != null) refuse(event, reason) else showList()
            }
        }
        addButton("Not now") { showList() }
        root.scrollTo(0, 0)
        root.requestFocus()
    }

    private fun addText(text: String, size: Float, color: Int) {
        column.addView(
            TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(color)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8 }
        )
    }

    private fun addButton(label: String, action: suspend () -> Unit) {
        column.addView(
            Button(this).apply {
                text = label
                textSize = 14f
                isAllCaps = false
                setOnClickListener { scope.launch { action() } }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 12 }
        )
    }

    /** The watch's own 12/24-hour setting ([DateFormat.is24HourFormat]). */
    private val timeFormat by lazy { DateFormat.getTimeFormat(this) }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The phone's instance key of an event to open straight to its confirm. */
        const val EXTRA_EVENT_KEY = "event_key"
        private val BACKGROUND = Color.parseColor("#0F1417")
    }
}
