package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.wear.tiles.TileService
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs a pomodoro on the watch itself — no phone, no network.
 *
 * Every user action and every phase end goes through here: the pure transition
 * from [WatchTimer], then the side effects in one place — persist, re-arm the
 * phase-end alarm, refresh the Now Bar item and tile, buzz, and hand a finished
 * focus block to [FocusSessionOutbox] for the phone to pick up whenever it can.
 *
 * Serialised by a [Mutex] because two callers race for the same phase end by
 * design: the open screen's own countdown reaching zero, and the alarm. Each
 * re-checks [WatchTimerState.isDue] under the lock, so the block is credited once.
 */
object WatchTimerEngine {

    private val mutex = Mutex()

    fun state(context: Context): WatchTimerState = WatchTimerStore.read(context)

    suspend fun start(context: Context, session: WatchSession = WatchSession.FREE) = mutate(context) {
        val stats = WearStatsReader.read(context)
        WatchTimer.start(stats.durations, stats.subject, System.currentTimeMillis(), session)
    }

    suspend fun pause(context: Context) = mutate(context) { WatchTimer.pause(it, System.currentTimeMillis()) }

    suspend fun resume(context: Context) = mutate(context) { WatchTimer.resume(it, System.currentTimeMillis()) }

    suspend fun stop(context: Context) = mutate(context) { WatchTimer.stop() }

    suspend fun skipBreak(context: Context) = mutate(context) {
        WatchTimer.skipBreak(it, System.currentTimeMillis()).also { next ->
            // A tap the user just made needs no alarm-strength announcement.
            if (next != it) buzz(context, PATTERN_FOCUS)
        }
    }

    /** Ends the current phase if its time has come; a no-op otherwise. */
    suspend fun advanceIfDue(context: Context) {
        var block: FocusBlock? = null
        var alert: (() -> Unit)? = null
        mutate(context) { current ->
            val advance = WatchTimer.advance(current, System.currentTimeMillis(), ZoneId.systemDefault())
            val next = advance.state
            alert = when {
                advance.sessionFinished -> {
                    val blocks = current.completedFocus + 1
                    { buzz(context, PATTERN_DONE); TimerNotifier.sessionComplete(context, blocks) }
                }
                next != current -> {
                    {
                        buzz(context, if (next.phase == WatchPhase.FOCUS) PATTERN_FOCUS else PATTERN_BREAK)
                        TimerNotifier.phaseAlert(context, next)
                    }
                }
                else -> null
            }
            block = advance.completed
            next
        }
        alert?.invoke()
        block?.let { FocusSessionOutbox.enqueue(context, it) }
    }

    /**
     * After a reboot every alarm is gone. A phase that is still running is
     * re-armed; one that ended while the watch was off is closed (its focus
     * block credited — that time did pass) and the run stopped, rather than
     * quietly starting a fresh phase hours after anyone was watching it.
     */
    suspend fun restoreAfterBoot(context: Context) {
        val now = System.currentTimeMillis()
        var block: FocusBlock? = null
        mutate(context) { current ->
            // Not due: writing it back unchanged is what re-arms the alarm.
            if (!current.isDue(now)) return@mutate current
            block = WatchTimer.advance(current, now, ZoneId.systemDefault()).completed
            WatchTimer.stop()
        }
        block?.let { FocusSessionOutbox.enqueue(context, it) }
    }

    private suspend fun mutate(context: Context, transform: suspend (WatchTimerState) -> WatchTimerState) {
        val app = context.applicationContext
        mutex.withLock {
            val next = transform(WatchTimerStore.read(app))
            WatchTimerStore.write(app, next)
            arm(app, next)
        }
        TimerNotifier.refresh(app)
        TileService.getUpdater(app).requestUpdate(StatsTileService::class.java)
    }

    private fun arm(context: Context, state: WatchTimerState) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PhaseEndReceiver::class.java).setAction(PhaseEndReceiver.ACTION_PHASE_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (!state.active || !state.running) {
            alarmManager.cancel(intent)
            return
        }
        // Exact when allowed (USE_EXACT_ALARM, or SCHEDULE_EXACT_ALARM before 33).
        // The inexact fallback can land minutes late in Doze — the break starts
        // late, but nothing is lost: the block is still credited when it fires.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, state.phaseEndsAt, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, state.phaseEndsAt, intent)
        }
    }

    /**
     * The transition alert, together with [TimerNotifier.phaseAlert]. Made hard
     * to miss on purpose: a buzz you sleep through on a raise-to-wake watch is a
     * break that turns into an hour.
     *
     * * Full motor strength (amplitude 255) where the motor supports it.
     * * Played as an ALARM vibration, not a notification one, so silent mode and
     *   notification muting do not swallow it — a timer is exactly what the alarm
     *   usage is for. Do Not Disturb that also blocks alarms still wins, as it
     *   should.
     * * Patterns told apart by feel: three long pulses into a break, five quick
     *   ones back to focus, three very long ones when a timed session ends.
     */
    private fun buzz(context: Context, pattern: LongArray) {
        if (context.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        val effect = if (vibrator.hasAmplitudeControl()) {
            // Even indices are gaps, odd ones are pulses: gaps silent, pulses flat out.
            val amplitudes = IntArray(pattern.size) { i -> if (i % 2 == 0) 0 else 255 }
            VibrationEffect.createWaveform(pattern, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(pattern, -1)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        }
    }

    private val PATTERN_BREAK = longArrayOf(0, 800, 300, 800, 300, 800)
    private val PATTERN_FOCUS = longArrayOf(0, 300, 150, 300, 150, 300, 150, 300, 150, 300)
    private val PATTERN_DONE = longArrayOf(0, 1200, 300, 1200, 300, 1200)
}

/**
 * Delivers the watch timer's phase-end alarm, and re-arms it after a reboot or
 * an app update (both of which drop pending alarms).
 */
class PhaseEndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_PHASE_END -> WatchTimerEngine.advanceIfDue(app)
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> WatchTimerEngine.restoreAfterBoot(app)
                }
                // Any reconnect-free moment is a fine one to retry a stranded block.
                FocusSessionOutbox.flush(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PHASE_END = "com.ogenrwotdaniel.wakeproof.wear.PHASE_END"
    }
}
