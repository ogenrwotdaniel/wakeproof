package com.ogenrwotdaniel.wakeproof.pomodoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.ogenrwotdaniel.wakeproof.AppForeground
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pomodoro cycle phases. */
enum class PomodoroPhase(val label: String) {
    FOCUS("Focus"),
    SHORT_BREAK("Short break"),
    LONG_BREAK("Long break")
}

/** Live timer state, observed by the UI via [PomodoroTimerService.state]. */
data class PomodoroState(
    val active: Boolean = false,
    val running: Boolean = false,
    val phase: PomodoroPhase = PomodoroPhase.FOCUS,
    val remainingSeconds: Int = 25 * 60,
    val totalSeconds: Int = 25 * 60,
    val completedFocusSessions: Int = 0,
    /** Focus blocks in this session; 0 = keep looping until stopped. */
    val targetFocusBlocks: Int = 0
)

/**
 * Foreground service (specialUse: countdown timer) so Pomodoro cycles survive
 * backgrounding. Publishes state through a process-wide StateFlow; completed
 * focus phases are logged to Room. Gentle chime + vibration on transitions.
 */
@AndroidEntryPoint
class PomodoroTimerService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var completionRepository: CompletionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var focusSeconds = 25 * 60
    private var shortBreakSeconds = 5 * 60
    private var longBreakSeconds = 15 * 60
    private var musicPlayer: MediaPlayer? = null
    private var currentSound: FocusSound? = null
    private val overlay by lazy { FloatingTimerOverlay(this) }

    override fun onCreate() {
        super.onCreate()
        // Ambient sound rides the timer: focus and break phases each have their
        // own toggle, and switching the selected sound swaps the loop live.
        scope.launch {
            combine(state, settingsRepository.settings) { s, cfg ->
                val wanted = s.active && s.running &&
                    if (s.phase == PomodoroPhase.FOCUS) cfg.focusMusicEnabled
                    else cfg.breakMusicEnabled
                wanted to FocusSound.fromId(cfg.focusSoundId)
            }.distinctUntilChanged().collect { (shouldPlay, sound) ->
                if (sound != currentSound) {
                    musicPlayer?.release()
                    musicPlayer = null
                    currentSound = sound
                }
                if (shouldPlay) startMusic(sound) else pauseMusic()
            }
        }
        // Floating countdown bubble: visible whenever a timer is active but the
        // app UI isn't. Runs on Main (scope's dispatcher), as WindowManager requires.
        scope.launch {
            combine(state, settingsRepository.settings, AppForeground.visible) { s, cfg, appVisible ->
                s to (cfg.focusFloatingTimer && s.active && !appVisible)
            }.collect { (s, show) ->
                if (show) {
                    overlay.show()
                    overlay.update(s)
                } else {
                    overlay.hide()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createChannel()
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                val sessionMinutes = intent.getIntExtra(EXTRA_SESSION_MINUTES, 0)
                scope.launch {
                    val settings = settingsRepository.settings.first()
                    focusSeconds = settings.focusMinutes * 60
                    shortBreakSeconds = settings.shortBreakMinutes * 60
                    longBreakSeconds = settings.longBreakMinutes * 60
                    // A "1 h" session with 25+5 settings = 2 focus blocks; "2 h" = 4.
                    val cycleMinutes = settings.focusMinutes + settings.shortBreakMinutes
                    val target =
                        if (sessionMinutes <= 0) 0
                        else (sessionMinutes / cycleMinutes).coerceAtLeast(1)
                    _state.value = PomodoroState(
                        active = true,
                        running = true,
                        phase = PomodoroPhase.FOCUS,
                        remainingSeconds = focusSeconds,
                        totalSeconds = focusSeconds,
                        targetFocusBlocks = target
                    )
                    startTicker()
                    updateNotification()
                }
            }
            ACTION_PAUSE -> {
                tickerJob?.cancel()
                _state.update { it.copy(running = false) }
                updateNotification()
            }
            ACTION_RESUME -> {
                _state.update { it.copy(running = true) }
                startTicker()
                updateNotification()
            }
            ACTION_RESET -> {
                tickerJob?.cancel()
                _state.update {
                    it.copy(
                        running = false,
                        phase = PomodoroPhase.FOCUS,
                        remainingSeconds = focusSeconds,
                        totalSeconds = focusSeconds
                    )
                }
                updateNotification()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        scope.cancel()
        overlay.hide()
        musicPlayer?.release()
        musicPlayer = null
        _state.value = PomodoroState()
        super.onDestroy()
    }

    private fun startMusic(sound: FocusSound) {
        runCatching {
            val player = musicPlayer ?: MediaPlayer.create(
                this, sound.res, // seamless ambient loop, generated asset
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                0
            )?.apply {
                isLooping = true
                val volume = (MUSIC_VOLUME * sound.gain).coerceAtMost(1f)
                setVolume(volume, volume)
            }.also { musicPlayer = it }
            if (player?.isPlaying == false) player.start()
        }
    }

    private fun pauseMusic() {
        runCatching { if (musicPlayer?.isPlaying == true) musicPlayer?.pause() }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (_state.value.running) {
                delay(1_000)
                val current = _state.value
                if (!current.running) break
                if (current.remainingSeconds <= 1) {
                    onPhaseComplete()
                } else {
                    _state.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
                }
                updateNotification()
            }
        }
    }

    private suspend fun onPhaseComplete() {
        chime()
        val current = _state.value
        if (current.phase == PomodoroPhase.FOCUS) {
            runCatching {
                completionRepository.log(
                    GoalKind.FOCUS, CompletionStatus.VERIFIED, amount = focusSeconds / 60,
                    subject = settingsRepository.settings.first().activeSubject
                )
            }
            val done = current.completedFocusSessions + 1
            // Timed session finished: skip the trailing break and stop cleanly.
            if (current.targetFocusBlocks in 1..done) {
                getSystemService(NotificationManager::class.java).notify(
                    SESSION_DONE_NOTIFICATION_ID,
                    Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("Session complete 🎉")
                        .setContentText("$done pomodoros done — great work.")
                        .setAutoCancel(true)
                        .build()
                )
                stopSelf()
                return
            }
            val next = if (done % SESSIONS_PER_LONG_BREAK == 0) PomodoroPhase.LONG_BREAK
            else PomodoroPhase.SHORT_BREAK
            val duration = durationOf(next)
            _state.update {
                it.copy(
                    phase = next,
                    remainingSeconds = duration,
                    totalSeconds = duration,
                    completedFocusSessions = done
                )
            }
        } else {
            _state.update {
                it.copy(
                    phase = PomodoroPhase.FOCUS,
                    remainingSeconds = focusSeconds,
                    totalSeconds = focusSeconds
                )
            }
        }
    }

    private fun durationOf(phase: PomodoroPhase) = when (phase) {
        PomodoroPhase.FOCUS -> focusSeconds
        PomodoroPhase.SHORT_BREAK -> shortBreakSeconds
        PomodoroPhase.LONG_BREAK -> longBreakSeconds
    }

    private fun chime() {
        runCatching {
            RingtoneManager.getRingtone(
                this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )?.play()
        }
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION)
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Pomodoro timer", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Live Pomodoro countdown" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val s = _state.value
        val minutes = s.remainingSeconds / 60
        val seconds = s.remainingSeconds % 60
        val text = "${s.phase.label} — %d:%02d".format(minutes, seconds) +
            if (s.running) "" else " (paused)"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val s = _state.value
        val toggleAction = if (s.running) {
            Notification.Action.Builder(
                null, "Pause", servicePendingIntent(ACTION_PAUSE, 1)
            ).build()
        } else {
            Notification.Action.Builder(
                null, "Resume", servicePendingIntent(ACTION_RESUME, 2)
            ).build()
        }
        val stopAction = Notification.Action.Builder(
            null, "Stop", servicePendingIntent(ACTION_STOP, 3)
        ).build()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pomodoro — ${s.phase.label}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Marks it as a timer so the paired watch surfaces it as an ongoing
            // activity; not setLocalOnly, so it bridges to the watch.
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .addAction(toggleAction)
            .addAction(stopAction)
        if (s.active && s.running) {
            // Self-ticking countdown: the watch renders a live timer off the
            // bridged notification without a per-second Bluetooth sync.
            builder.setWhen(System.currentTimeMillis() + s.remainingSeconds * 1_000L)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false).setUsesChronometer(false)
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PomodoroTimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private val _state = MutableStateFlow(PomodoroState())

        /** Process-wide observable timer state. */
        val state: StateFlow<PomodoroState> = _state.asStateFlow()

        private const val ACTION_START = "com.ogenrwotdaniel.wakeproof.action.POMODORO_START"
        private const val ACTION_PAUSE = "com.ogenrwotdaniel.wakeproof.action.POMODORO_PAUSE"
        private const val ACTION_RESUME = "com.ogenrwotdaniel.wakeproof.action.POMODORO_RESUME"
        private const val ACTION_RESET = "com.ogenrwotdaniel.wakeproof.action.POMODORO_RESET"
        private const val ACTION_STOP = "com.ogenrwotdaniel.wakeproof.action.POMODORO_STOP"
        private const val CHANNEL_ID = "wakeproof_pomodoro"
        private const val NOTIFICATION_ID = 2
        private const val SESSION_DONE_NOTIFICATION_ID = 3
        private const val SESSIONS_PER_LONG_BREAK = 4
        private const val MUSIC_VOLUME = 0.55f
        private const val EXTRA_SESSION_MINUTES = "session_minutes"

        /** [sessionMinutes] > 0 runs a timed session (e.g. 60 or 120); 0 loops forever. */
        fun start(context: Context, sessionMinutes: Int = 0) = context.startForegroundService(
            intent(context, ACTION_START).putExtra(EXTRA_SESSION_MINUTES, sessionMinutes)
        )
        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        fun reset(context: Context) = context.startService(intent(context, ACTION_RESET))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        private fun intent(context: Context, action: String) =
            Intent(context, PomodoroTimerService::class.java).setAction(action)
    }
}
