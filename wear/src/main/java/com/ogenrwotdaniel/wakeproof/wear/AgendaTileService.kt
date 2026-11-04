package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.text.format.DateFormat
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * "WakeProof Today" (2026-11-04): the rest of today's calendar on a tile —
 * up to [AgendaOffers.TILE_MAX_EVENTS] ongoing or upcoming, not-ticked timed
 * events from the phone's `/wakeproof/agenda` push, plus a line when a focus
 * session runs on the watch or the phone.
 *
 * Each event row opens [AgendaActivity] straight at that event's confirm (or
 * the reason it cannot be focused for); the header opens the full list.
 *
 * Refreshed by [StatsDataListenerService] on an agenda or phone-timer push, by
 * [WatchTimerEngine] on every watch-timer change ([requestUpdate]), and by its
 * own freshness interval, which runs only until the next event starts or ends
 * so a finished event drops off without a push.
 */
class AgendaTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val context = this@AgendaTileService
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val todayDate = LocalDate.now(zone)
        val agenda = WatchAgenda.read(context)
        val todays = agenda.forDay(todayDate.toEpochDay())
        val shown = AgendaOffers.tileEvents(todays, now)
        val session = AgendaOffers.runningSession(
            WatchTimerEngine.state(context), TimerSnapshot.read(context), now
        )
        val midnight = todayDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val boundaries = buildList {
            todays.filter { !it.done }.forEach { add(it.beginMillis); add(it.endMillis) }
            when (session) {
                is RunningSession.Watch -> add(session.endsAt)
                is RunningSession.Phone -> session.blockEndsAt?.let {
                    add(it)
                    // A phone that never pushes the next phase is dead: drop the line then.
                    add(it + TimerSnapshot.RUNNING_STALE_MILLIS)
                }
                null -> Unit
            }
            add(midnight)
        }
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(AgendaOffers.tileFreshnessMillis(now, boundaries))
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    layout(agenda, todayDate.toEpochDay(), todays, shown, session, now)
                )
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun time(millis: Long): String = DateFormat.getTimeFormat(this).format(Date(millis))

    private fun layout(
        agenda: WatchAgendaList,
        today: Long,
        todays: List<WatchAgendaEvent>,
        shown: List<WatchAgendaEvent>,
        session: RunningSession?,
        now: Long
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)

        column.addContent(
            text("Today", Typography.TYPOGRAPHY_TITLE3, COLOR_ACCENT, 1, openAgenda("header", null))
        )
        when (session) {
            is RunningSession.Watch ->
                column.addContent(text("Focus running · ends ${time(session.endsAt)}", Typography.TYPOGRAPHY_CAPTION2, COLOR_MUTED))
            is RunningSession.Phone ->
                column.addContent(
                    text(
                        session.blockEndsAt?.let { "Focus on phone · block ends ${time(it)}" } ?: "Focus running on phone",
                        Typography.TYPOGRAPHY_CAPTION2,
                        COLOR_MUTED
                    )
                )
            null -> Unit
        }
        column.addContent(spacer())

        val empty = when {
            !agenda.present -> "Open WakeProof on the phone"
            !agenda.allowed -> "Calendar access is off on the phone"
            agenda.epochDay != today -> "Not synced today yet. Open WakeProof on the phone"
            shown.isEmpty() && todays.isEmpty() -> "Nothing timed on the calendar today"
            shown.isEmpty() -> "Nothing left today"
            else -> null
        }
        if (empty != null) {
            column.addContent(text(empty, Typography.TYPOGRAPHY_BODY2, COLOR_MUTED, 3, openAgenda("empty", null)))
        } else {
            shown.forEachIndexed { index, event ->
                column.addContent(
                    text(
                        "${time(event.beginMillis)} ${event.title}",
                        Typography.TYPOGRAPHY_BODY2,
                        COLOR_TEXT,
                        1,
                        openAgenda("event$index", event.key)
                    )
                )
            }
            val more = todays.count { !it.done && it.endMillis > now } - shown.size
            if (more > 0) {
                column.addContent(text("+ $more more", Typography.TYPOGRAPHY_CAPTION2, COLOR_MUTED, 1, openAgenda("more", null)))
            }
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(SIDE_PADDING_DP))
                            .setEnd(dp(SIDE_PADDING_DP))
                            .build()
                    )
                    .build()
            )
            .addContent(column.build())
            .build()
    }

    /** Opens [AgendaActivity], at [eventKey]'s confirm when given. Ids must be unique per tile. */
    private fun openAgenda(id: String, eventKey: String?): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(AgendaActivity::class.java.name)
        if (eventKey != null) {
            activity.addKeyToExtraMapping(
                AgendaActivity.EXTRA_EVENT_KEY,
                ActionBuilders.AndroidStringExtra.Builder().setValue(eventKey).build()
            )
        }
        return ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    private fun text(
        value: String,
        typography: Int,
        color: Int,
        maxLines: Int = 2,
        clickable: ModifiersBuilders.Clickable? = null
    ): LayoutElementBuilders.LayoutElement {
        val builder = Text.Builder(this, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
        if (clickable != null) {
            builder.setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setPadding(ModifiersBuilders.Padding.Builder().setTop(dp(3f)).setBottom(dp(3f)).build())
                    .build()
            )
        }
        return builder.build()
    }

    private fun spacer(): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build()

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val SIDE_PADDING_DP = 28f

        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFFB0BEC5.toInt()
        private const val COLOR_ACCENT = 0xFFFF7043.toInt()

        /** Asks the system to re-render the tile; cheap, and a no-op if it is not added. */
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.getUpdater(context).requestUpdate(AgendaTileService::class.java)
            }
        }
    }
}
