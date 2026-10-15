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
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationManagerCompat
import androidx.wear.tiles.TileService
import java.time.LocalDate
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
 * phase-end alarm, refresh the Now Bar item and tile, buzz ([Buzzer]), and hand a finished
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

    // A user action is an acknowledgement: whatever is still buzzing stops.

    suspend fun pause(context: Context) {
        Buzzer.stop()
        mutate(context) { WatchTimer.pause(it, System.currentTimeMillis()) }
    }

    suspend fun resume(context: Context) {
        Buzzer.stop()
        mutate(context) { WatchTimer.resume(it, System.currentTimeMillis()) }
    }

    /**
     * Ends the run. Blocks already finished are credited and summed up in a
     * "Session ended" notification — for a free run, stopping IS how the
     * session ends, so this is its "all focus done". A block in progress is
     * not credited, as on the phone.
     */
    suspend fun stop(context: Context) {
        Buzzer.stop()
        val app = context.applicationContext
        var prior: WatchTimerState? = null
        mutate(app) { prior = it; WatchTimer.stop() }
        val ended = prior ?: return
        if (ended.active && ended.completedFocus > 0) {
            TimerNotifier.sessionSummary(
                app,
                complete = false,
                blocks = ended.completedFocus,
                minutes = ended.completedFocus * ended.durations.focusMinutes,
                day = focusDay(app)
            )
        } else {
            TimerNotifier.clearPhaseAlert(app)
        }
    }

    suspend fun skipBreak(context: Context) = mutate(context) {
        WatchTimer.skipBreak(it, System.currentTimeMillis()).also { next ->
            // A tap the user just made needs no alarm-strength announcement.
            if (next != it) Buzzer.play(context, BuzzPattern.TAP)
        }
    }

    /**
     * Ends the current phase if its time has come; a no-op otherwise.
     *
     * Buzzes FIRST, before the Data Layer reads below: the buzz is the alert
     * and should land on the second the phase ends, the notification text can
     * follow a moment later.
     */
    suspend fun advanceIfDue(context: Context) {
        val app = context.applicationContext
        var prior: WatchTimerState? = null
        var step: Advance? = null
        mutate(app) { current ->
            val advance = WatchTimer.advance(current, System.currentTimeMillis(), ZoneId.systemDefault())
            prior = current
            step = advance
            advance.state
        }
        val before = prior ?: return
        val advance = step ?: return
        if (advance.state == before) return // nothing was due

        Buzzer.play(
            app,
            when {
                advance.sessionFinished -> BuzzPattern.SESSION_DONE
                advance.state.phase == WatchPhase.FOCUS -> BuzzPattern.INTO_FOCUS
                else -> BuzzPattern.INTO_BREAK
            }
        )

        var day: FocusDay? = null
        var goalReached = false
        advance.completed?.let { block ->
            // Today BEFORE this block, so the goal crossing is seen exactly once.
            val dayBefore = focusDay(app)
            FocusSessionOutbox.enqueue(app, block)
            val added = if (block.epochDay == todayEpochDay()) block.minutes else 0
            day = dayBefore.copy(minutes = dayBefore.minutes + added)
            goalReached = crossedGoal(dayBefore.minutes, added, dayBefore.goal)
        }

        if (advance.sessionFinished) {
            val blocks = before.completedFocus + 1
            TimerNotifier.sessionSummary(
                app,
                complete = true,
                blocks = blocks,
                minutes = blocks * before.durations.focusMinutes,
                day = day
            )
        } else {
            TimerNotifier.phaseAlert(app, advance.state)
        }
        day?.takeIf { goalReached }?.let { TimerNotifier.goalReached(app, it) }
    }

    /** Today's focus as the watch sees it: the phone's last figure plus unsynced blocks. */
    private suspend fun focusDay(context: Context): FocusDay {
        val zone = ZoneId.systemDefault()
        val stats = WearStatsReader.read(context)
        val today = focusToday(stats, FocusSessionOutbox.pending(context), todayEpochDay(), zone)
        return FocusDay(minutes = today.minutes, goal = stats.focusGoal)
    }

    private fun todayEpochDay() = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

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

/**
 * Plays the phase-change buzz, and stops it the moment the user acknowledges it.
 *
 * * Full motor strength (amplitude 255) where the motor supports it.
 * * Played as an ALARM vibration, not a notification one, so silent mode and
 *   notification muting do not swallow it — a timer is exactly what the alarm
 *   usage is for. Do Not Disturb that also blocks alarms still wins, as it
 *   should.
 * * Stoppable, because a 30-second buzz that cannot be stopped is a punishment:
 *   any touch on [MainActivity], opening it, and "Got it" / swiping away the
 *   alert ([StopBuzzReceiver]) all end it.
 *
 * Holds ONE [Vibrator], from the application context, for both play and stop:
 * the vibrator service only cancels a vibration for the same client token, and
 * each Context hands out its own Vibrator — one fetched from an Activity could
 * not stop a buzz started from a receiver.
 *
 * The vibration lives in the system service, not in this process, but the
 * service drops it if this process dies mid-buzz. Nothing here prevents that;
 * in practice a watch does not reclaim an app within 30 s of a broadcast.
 */
object Buzzer {

    @Volatile private var vibrator: Vibrator? = null
    /** elapsedRealtime when the current buzz ends; stop() is free after that. */
    @Volatile private var playingUntil = 0L

    fun play(context: Context, pattern: LongArray) {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) return
        val vib = vibrator ?: app.getSystemService(Vibrator::class.java)?.also { vibrator = it } ?: return
        val effect = if (vib.hasAmplitudeControl()) {
            // Even indices are gaps, odd ones are pulses: gaps silent, pulses flat out.
            val amplitudes = IntArray(pattern.size) { i -> if (i % 2 == 0) 0 else 255 }
            VibrationEffect.createWaveform(pattern, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(pattern, -1)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
            playingUntil = SystemClock.elapsedRealtime() + BuzzPattern.lengthOf(pattern)
        }
    }

    /** Silences the current buzz. Cheap to call from every touch: a no-op when idle. */
    fun stop() {
        if (SystemClock.elapsedRealtime() >= playingUntil) return
        playingUntil = 0L
        runCatching { vibrator?.cancel() }
    }
}

/** "Got it" on a phase alert, or the alert swiped away: stop buzzing, clear the alert. */
class StopBuzzReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Buzzer.stop()
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) NotificationManagerCompat.from(context).cancel(id)
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
