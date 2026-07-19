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
    val completedFocusSessions: Int = 0
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

    override fun onCreate() {
        super.onCreate()
        // Study music rides the focus phase: plays while a focus countdown is
        // running (and the setting is on), pauses on breaks/pause/stop.
        scope.launch {
            combine(state, settingsRepository.settings) { s, cfg ->
                cfg.focusMusicEnabled && s.active && s.running && s.phase == PomodoroPhase.FOCUS
            }.distinctUntilChanged().collect { shouldPlay ->
                if (shouldPlay) startMusic() else pauseMusic()
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
                scope.launch {
                    val settings = settingsRepository.settings.first()
                    focusSeconds = settings.focusMinutes * 60
                    shortBreakSeconds = settings.shortBreakMinutes * 60
                    longBreakSeconds = settings.longBreakMinutes * 60
                    _state.value = PomodoroState(
                        active = true,
                        running = true,
                        phase = PomodoroPhase.FOCUS,
                        remainingSeconds = focusSeconds,
                        totalSeconds = focusSeconds
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
        musicPlayer?.release()
        musicPlayer = null
        _state.value = PomodoroState()
        super.onDestroy()
    }

    private fun startMusic() {
        runCatching {
            val player = musicPlayer ?: MediaPlayer.create(
                this, R.raw.focus_ambient, // seamless ambient loop, generated asset
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                0
            )?.apply {
                isLooping = true
                setVolume(MUSIC_VOLUME, MUSIC_VOLUME)
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
                    GoalKind.FOCUS, CompletionStatus.VERIFIED, amount = focusSeconds / 60
                )
            }
            val done = current.completedFocusSessions + 1
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
        val toggleAction = if (_state.value.running) {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pomodoro")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(toggleAction)
            .addAction(stopAction)
            .build()
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
        private const val SESSIONS_PER_LONG_BREAK = 4
        private const val MUSIC_VOLUME = 0.55f

        fun start(context: Context) = context.startForegroundService(intent(context, ACTION_START))
        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        fun reset(context: Context) = context.startService(intent(context, ACTION_RESET))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        private fun intent(context: Context, action: String) =
            Intent(context, PomodoroTimerService::class.java).setAction(action)
    }
}
