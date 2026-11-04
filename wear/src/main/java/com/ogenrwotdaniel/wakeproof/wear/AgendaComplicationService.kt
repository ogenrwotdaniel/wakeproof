package com.ogenrwotdaniel.wakeproof.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.LocalDate
import java.util.Date

/**
 * "WakeProof Today" as a watch-face complication (2026-11-04): the slot the
 * calendar complication used to fill, showing the rest of today's events from
 * the phone's `/wakeproof/agenda` push — or the running focus session — with
 * a tap that opens [AgendaActivity] at the next event's confirm, exactly like
 * a row on [AgendaTileService]. Wording is [AgendaComplicationTexts] (pure).
 *
 * Supports LONG_TEXT then SHORT_TEXT. Anything else gets no data.
 *
 * FRESHNESS: pushed by [AgendaSurfaces.refresh] on every agenda push, phone
 * timer push and watch-timer change, plus the system's UPDATE_PERIOD_SECONDS
 * (900) in the manifest. Nothing else is scheduled, deliberately: an event
 * that ends with no push behind it can linger for up to 15 minutes, which is
 * the price of not waking the watch at every event boundary. (The tile's own
 * freshness interval does not reach the complication.)
 */
class AgendaComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val type = request.complicationType
        if (type != ComplicationType.LONG_TEXT && type != ComplicationType.SHORT_TEXT) return null
        val now = System.currentTimeMillis()
        val content = AgendaComplicationTexts.content(
            agenda = WatchAgenda.read(this),
            today = LocalDate.now().toEpochDay(),
            session = AgendaOffers.runningSession(WatchTimerEngine.state(this), TimerSnapshot.read(this), now),
            now = now,
            time = ::time
        )
        return build(type, content, tapIntent(content.tapEventKey))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(
        type,
        AgendaComplicationContent(
            longTitle = "17:30 Problem Solving",
            longText = "+2 more",
            shortTitle = "Next",
            shortText = "17:30",
            description = "WakeProof Today: 17:30 Problem Solving and 2 more",
            tapEventKey = null
        ),
        tap = null
    )

    private fun time(millis: Long): String = DateFormat.getTimeFormat(this).format(Date(millis))

    private fun build(type: ComplicationType, content: AgendaComplicationContent, tap: PendingIntent?): ComplicationData? {
        val description = plain(content.description)
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(content.longText), description)
                .apply { content.longTitle?.let { setTitle(plain(it)) } }
                .setTapAction(tap)
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(content.shortText), description)
                .apply { content.shortTitle?.let { setTitle(plain(it)) } }
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()

    /** Unique request code per event key, so two keys never share (and overwrite) one PendingIntent. */
    private fun tapIntent(eventKey: String?): PendingIntent {
        val intent = Intent(this, AgendaActivity::class.java)
        eventKey?.let { intent.putExtra(AgendaActivity.EXTRA_EVENT_KEY, it) }
        return PendingIntent.getActivity(
            this,
            eventKey?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        /** Re-renders every instance on any watch face; a no-op when none is placed. */
        fun requestUpdate(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    context.applicationContext,
                    ComponentName(context.applicationContext, AgendaComplicationService::class.java)
                ).requestUpdateAll()
            }
        }
    }
}

/**
 * Everything that shows "WakeProof Today": the tile and the complication. One
 * call so a new surface cannot be refreshed in some places and forgotten in others.
 */
object AgendaSurfaces {
    fun refresh(context: Context) {
        AgendaTileService.requestUpdate(context)
        AgendaComplicationService.requestUpdate(context)
    }
}
