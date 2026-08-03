package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Timer contract keys — MUST match app `WearContract` timer keys (kept in sync by hand). */
internal object TimerContract {
    const val PATH = "/wakeproof/timer"
    const val KEY_ACTIVE = "t_active"
    const val KEY_RUNNING = "t_running"
    const val KEY_PHASE = "t_phase"
    const val KEY_REMAINING = "t_remaining"
    const val KEY_FINISH_AT = "t_finish_at"
}

/** A single push of Pomodoro timer state from the phone. */
data class TimerSnapshot(
    val active: Boolean,
    val running: Boolean,
    val phase: String,
    val remainingSeconds: Int,
    val finishAtMillis: Long
) {
    companion object {
        fun from(map: DataMap) = TimerSnapshot(
            active = map.getBoolean(TimerContract.KEY_ACTIVE, false),
            running = map.getBoolean(TimerContract.KEY_RUNNING, false),
            phase = map.getString(TimerContract.KEY_PHASE, ""),
            remainingSeconds = map.getInt(TimerContract.KEY_REMAINING, 0),
            finishAtMillis = map.getLong(TimerContract.KEY_FINISH_AT, 0L)
        )

        /**
         * Reads the latest timer snapshot the phone pushed onto the Data Layer,
         * or null if none / not active. Mirrors [WearStatsReader]'s read path so
         * [MainActivity] can show the live countdown while a Pomodoro runs.
         */
        suspend fun read(context: Context): TimerSnapshot? = withContext(Dispatchers.IO) {
            val buffer = runCatching {
                Wearable.getDataClient(context).dataItems.await()
            }.getOrNull() ?: return@withContext null
            try {
                val item = buffer.firstOrNull { it.uri.path == TimerContract.PATH }
                    ?: return@withContext null
                from(DataMapItem.fromDataItem(item).dataMap).takeIf { it.active }
            } finally {
                buffer.release()
            }
        }
    }
}

/**
 * Posts / updates / cancels the watch's Ongoing Activity for the running
 * Pomodoro. When running, a [Status.TimerPart] anchored to the phone's finish
 * time counts down on the watch itself — smooth ticking with no per-second sync.
 */
object TimerNotifier {
    private const val CHANNEL_ID = "wakeproof_timer"
    private const val NOTIFICATION_ID = 42

    fun update(context: Context, snapshot: TimerSnapshot?) {
        val manager = NotificationManagerCompat.from(context)
        if (snapshot == null || !snapshot.active) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        // POST_NOTIFICATIONS is declared, but the watch asks for it at runtime and
        // the user can say no — in which case there is no Now Bar item to post and
        // the whole build-up below is wasted work. Cancelling first keeps a stale
        // chip from outliving a revoked grant. Inlined rather than extracted to a
        // helper because lint only credits the guard inside the same function.
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(context)
        val touchIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val phase = snapshot.phase.ifBlank { "Focus" }
        val status = if (snapshot.running) {
            Status.Builder()
                .addTemplate("$phase #timer#")
                .addPart("timer", Status.TimerPart(snapshot.finishAtMillis))
                .build()
        } else {
            val minutes = snapshot.remainingSeconds / 60
            val seconds = snapshot.remainingSeconds % 60
            Status.Builder()
                .addTemplate("$phase paused #time#")
                .addPart("time", Status.TextPart("%d:%02d".format(minutes, seconds)))
                .build()
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("WakeProof")
            .setContentText(if (snapshot.running) phase else "$phase (paused)")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_foreground)
            .setTouchIntent(touchIntent)
            .setStatus(status)
            .build()
            .apply(context)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Pomodoro timer", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Live focus timer mirrored from the phone" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
