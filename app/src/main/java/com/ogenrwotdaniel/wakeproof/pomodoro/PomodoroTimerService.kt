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
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.wear.WearTimerSync
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

/** What the floating bubble should be doing right now. */
private data class OverlayTarget(
    val state: PomodoroState,
    val show: Boolean,
    val keepScreenOn: Boolean
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
    @Inject lateinit var wearTimerSync: WearTimerSync
    @Inject lateinit var spotify: SpotifyController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var focusSeconds = 25 * 60
    private var shortBreakSeconds = 5 * 60
    private var longBreakSeconds = 15 * 60
    private var musicPlayer: MediaPlayer? = null
    private var currentSound: FocusSound? = null
    /** Volume ramp in flight; cancelled whenever the target volume changes. */
    private var fadeJob: Job? = null
    /** MediaPlayer has no getVolume, so the ramp tracks where it left off. */
    private var musicVolume = 0f
    /** Latest settings, so the ticker can consult them without suspending. */
    private var latestSettings = WakeProofSettings()
    /** Short-lived player for the transition chime; released on completion. */
    private var chimePlayer: MediaPlayer? = null
    /** Last play/pause we sent Spotify; null = we have never touched playback. */
    private var spotifyPlaying: Boolean? = null
    private val overlay by lazy { FloatingTimerOverlay(this) }

    override fun onCreate() {
        super.onCreate()
        // Ambient sound rides the timer: focus and break phases each have their
        // own toggle, and switching the selected sound swaps the loop live. A
        // null sound means Spotify is the chosen source — no loop at all.
        scope.launch { settingsRepository.settings.collect { latestSettings = it } }
        scope.launch {
            combine(state, settingsRepository.settings) { s, cfg ->
                val sound = FocusSound.fromId(cfg.focusSoundId)
                Triple(sound != null && soundWanted(s, cfg), sound, cfg.soundFadeSeconds)
            }.distinctUntilChanged().collect { (shouldPlay, sound, fade) ->
                if (sound != currentSound) {
                    fadeJob?.cancel()
                    musicPlayer?.release()
                    musicPlayer = null
                    musicVolume = 0f
                    currentSound = sound
                }
                if (shouldPlay && sound != null) startMusic(sound, fade) else pauseMusic(fade)
            }
        }
        // Floating countdown bubble: visible whenever a timer is active but the
        // app UI isn't. Runs on Main (scope's dispatcher), as WindowManager requires.
        scope.launch {
            combine(state, settingsRepository.settings, AppForeground.visible) { s, cfg, appVisible ->
                OverlayTarget(
                    state = s,
                    show = cfg.focusFloatingTimer && s.active && !appVisible,
                    // Keeps the screen alive outside the app too, for as long as the
                    // bubble is up and the timer is counting down — breaks included.
                    keepScreenOn = cfg.sessionKeepScreenOn && s.active && s.running
                )
            }.collect { target ->
                if (target.show) {
                    overlay.show()
                    overlay.setKeepScreenOn(target.keepScreenOn)
                    overlay.update(target.state)
                } else {
                    overlay.hide()
                }
            }
        }
        // Spotify picked as the study sound: it rides the timer through the very
        // same phase toggles the loops use. Media keys only — playback resumes
        // when the phase wants sound and pauses on break/pause/stop. We never
        // send a pause before having sent a play, so a timer cannot silence
        // music the user started for something else.
        scope.launch {
            combine(state, settingsRepository.settings) { s, cfg ->
                cfg.focusSoundId == FocusSound.SPOTIFY_ID && soundWanted(s, cfg)
            }.distinctUntilChanged().collect { shouldPlay ->
                if (shouldPlay == spotifyPlaying) return@collect
                if (spotifyPlaying == null && !shouldPlay) return@collect
                spotifyPlaying = shouldPlay
                if (shouldPlay) spotify.play() else spotify.pause()
            }
        }
    }

    /**
     * Whether the phase the timer sits in wants sound at all — each phase has its
     * own toggle, and a paused or stopped timer is always silent. Shared by the
     * bundled loops and Spotify so exactly one of them can ever be playing.
     */
    private fun soundWanted(s: PomodoroState, cfg: WakeProofSettings) =
        s.active && s.running &&
            if (s.phase == PomodoroPhase.FOCUS) cfg.focusMusicEnabled else cfg.breakMusicEnabled

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
                    // See focusBlocksForSession for why this is not a division.
                    val target = focusBlocksForSession(
                        sessionMinutes = sessionMinutes,
                        focusMinutes = settings.focusMinutes,
                        breakMinutes = settings.shortBreakMinutes
                    )
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
                    syncWatch()
                }
            }
            ACTION_PAUSE -> {
                tickerJob?.cancel()
                _state.update { it.copy(running = false) }
                updateNotification()
                syncWatch()
            }
            ACTION_RESUME -> {
                _state.update { it.copy(running = true) }
                startTicker()
                updateNotification()
                syncWatch()
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
                syncWatch()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        // Best-effort: dismiss the watch timer chip. Detached from [scope] since
        // that is cancelled immediately below.
        CoroutineScope(Dispatchers.IO).launch { runCatching { wearTimerSync.clear() } }
        scope.cancel()
        // Session over: stop the music we started, but never touch playback we didn't.
        if (spotifyPlaying == true) {
            spotify.pause()
            spotifyPlaying = false
        }
        overlay.hide()
        // Stopping shouldn't cut the loop dead either, but [scope] is cancelled
        // above — hand the player to a detached ramp that releases it when done.
        val dying = musicPlayer
        val fade = latestSettings.soundFadeSeconds
        musicPlayer = null
        if (dying != null) {
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { ramp(dying, 0f, fade) }
                runCatching { dying.release() }
            }
        }
        chimePlayer?.release()
        chimePlayer = null
        _state.value = PomodoroState()
        super.onDestroy()
    }

    /**
     * Loudness the loop settles at once faded in.
     *
     * One number for every sound: the loops are levelled to a matched loudness
     * when they are generated (see `tools/sounds/README.md`), so there is
     * nothing left here to trim per sound.
     */
    private fun peakVolumeOf(@Suppress("UNUSED_PARAMETER") sound: FocusSound) = MUSIC_VOLUME

    private fun MediaPlayer.applyVolume(v: Float) {
        musicVolume = v
        setVolume(v, v)
    }

    /**
     * Walks the loop's volume to [to] over [seconds], on a curve rather than a
     * straight line: the ear hears loudness roughly logarithmically, so a linear
     * amplitude ramp sounds like it jumps at the quiet end and crawls at the loud
     * one. Squaring the progress spends more of the ramp down low, which is what
     * reads as a smooth fade.
     */
    private suspend fun ramp(player: MediaPlayer, to: Float, seconds: Int) {
        val from = musicVolume
        // A ramp that has nowhere to go is done immediately. The tolerance
        // matters at the phase boundary: the pre-rolled fade-out has already
        // walked the volume to within a rounding error of silence, and the
        // boundary then asks for the same fade again. Comparing exactly, that
        // second request spends the whole fade length crawling from 0.0001 to
        // 0 — inaudible, but it holds the player open for a minute after the
        // sound has gone, and at a 60 s fade that is most of a break.
        if (seconds <= 0 || kotlin.math.abs(from - to) < 0.001f) {
            runCatching { player.applyVolume(to) }
            return
        }
        val steps = seconds * FADE_STEPS_PER_SECOND
        val stepMillis = 1000L / FADE_STEPS_PER_SECOND
        for (step in 1..steps) {
            delay(stepMillis)
            val progress = step.toFloat() / steps
            val eased =
                if (to > from) progress * progress
                else 1f - (1f - progress) * (1f - progress)
            runCatching { player.applyVolume(from + (to - from) * eased) }
        }
    }

    private fun startMusic(sound: FocusSound, fadeSeconds: Int) {
        runCatching {
            val player = musicPlayer ?: MediaPlayer.create(
                this, sound.res, // seamless ambient loop, generated asset
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                0
            )?.apply { isLooping = true }.also { musicPlayer = it } ?: return
            val peak = peakVolumeOf(sound)
            fadeJob?.cancel()
            if (!player.isPlaying) {
                // Start from silence so the loop arrives under you rather than
                // snapping on at full level the instant the phase flips.
                player.applyVolume(if (fadeSeconds > 0) 0f else peak)
                player.start()
            }
            fadeJob = scope.launch { ramp(player, peak, fadeSeconds) }
        }
    }

    /**
     * Eases the loop down to silence and only then pauses it, so resuming later
     * picks the loop up where it left off instead of restarting the waveform.
     */
    private fun pauseMusic(fadeSeconds: Int) {
        val player = musicPlayer ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            if (runCatching { player.isPlaying }.getOrDefault(false)) {
                ramp(player, 0f, fadeSeconds)
                runCatching { player.pause() }
            }
        }
    }

    /**
     * Begins the fade-out [soundFadeSeconds] *before* the phase ends, so the loop
     * has reached silence by the time the chime lands — a fade that only starts at
     * the boundary bleeds into the next phase. Skipped when the next phase wants
     * sound anyway, since dipping to zero and climbing straight back is worse than
     * simply carrying on. Fires on the single tick where the countdown equals the
     * fade length, so repeated calls cannot restart the ramp and stall it short of
     * silence.
     */
    private fun maybeFadeOutBeforePhaseEnd(s: PomodoroState) {
        val fade = latestSettings.soundFadeSeconds
        if (fade <= 0 || s.remainingSeconds != fade) return
        if (musicPlayer?.isPlaying != true) return
        val nextPhaseWantsSound = if (s.phase == PomodoroPhase.FOCUS) {
            // A timed session that ends on this block goes quiet for good.
            val done = s.completedFocusSessions + 1
            s.targetFocusBlocks !in 1..done && latestSettings.breakMusicEnabled
        } else {
            latestSettings.focusMusicEnabled
        }
        if (!nextPhaseWantsSound) pauseMusic(fade)
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
                    maybeFadeOutBeforePhaseEnd(_state.value)
                }
                updateNotification()
            }
        }
    }

    private suspend fun onPhaseComplete() {
        val current = _state.value
        // The session-complete notification alerts loudly on its own channel, so
        // chiming here too would fire the same tone twice over itself.
        val finishing = current.phase == PomodoroPhase.FOCUS &&
            current.targetFocusBlocks in 1..(current.completedFocusSessions + 1)
        if (!finishing) chime()
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
                    Notification.Builder(this, ALERT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("Session complete 🎉")
                        .setContentText("$done pomodoros done — great work.")
                        .setAutoCancel(true)
                        .build()
                )
                runCatching { wearTimerSync.clear() }
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
        syncWatch()
    }

    private fun durationOf(phase: PomodoroPhase) = when (phase) {
        PomodoroPhase.FOCUS -> focusSeconds
        PomodoroPhase.SHORT_BREAK -> shortBreakSeconds
        PomodoroPhase.LONG_BREAK -> longBreakSeconds
    }

    /**
     * Phase-transition alert, deliberately loud: the tone is played through the
     * ALARM stream at full player volume rather than handed to [RingtoneManager],
     * whose notification stream sits far too low to be heard over study music or
     * from across the room — and a transition you miss defeats the timer. Falls
     * back to the plain ringtone if the player cannot be built.
     */
    private fun chime() {
        chimePlayer?.release()
        chimePlayer = null
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val started = uri != null && runCatching {
            MediaPlayer().also { player ->
                chimePlayer = player
                player.setAudioAttributes(ALERT_AUDIO_ATTRIBUTES)
                player.setDataSource(this, uri)
                player.setVolume(1f, 1f)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { done ->
                    done.release()
                    if (chimePlayer === done) chimePlayer = null
                }
                player.setOnErrorListener { _, _, _ -> true } // stay silent, never crash
                player.prepareAsync()
            }
        }.isSuccess
        if (!started) {
            chimePlayer = null
            runCatching { RingtoneManager.getRingtone(this, uri)?.play() }
        }
        // Two firm pulses at full amplitude: one soft buzz goes unnoticed in a pocket.
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 400, 200, 400), intArrayOf(0, 255, 0, 255), -1
            ),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Pomodoro timer", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live Pomodoro countdown" }
        )
        // Separate channel for the end of a session: the countdown itself has to
        // stay silent, but this one must cut through, so it alerts on the alarm
        // stream instead of the quieter notification stream.
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID, "Pomodoro alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Session-complete alerts"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    ALERT_AUDIO_ATTRIBUTES
                )
                enableVibration(true)
            }
        )
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

    /**
     * Pushes the current phase to the watch. Called only on transitions (not
     * every tick): the watch counts down on its own from the absolute finish
     * time, so this stays quiet while the timer just runs.
     */
    private fun syncWatch() {
        val s = _state.value
        val finishAt = if (s.running) System.currentTimeMillis() + s.remainingSeconds * 1_000L else 0L
        scope.launch {
            runCatching {
                wearTimerSync.push(s.active, s.running, s.phase.label, s.remainingSeconds, finishAt)
            }
        }
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
        private const val ALERT_CHANNEL_ID = "wakeproof_pomodoro_alerts"
        private const val NOTIFICATION_ID = 2
        private const val SESSION_DONE_NOTIFICATION_ID = 3
        private const val SESSIONS_PER_LONG_BREAK = 4
        /**
         * Playback level for the bundled loops. Raised from 0.55 when the assets
         * were regenerated at a matched -14 LUFS: the old set averaged louder in
         * the file, so holding 0.55 would have made every sound quieter than the
         * one the user had got used to.
         */
        private const val MUSIC_VOLUME = 0.7f

        /** Ramp resolution. 20 Hz is well under the threshold where steps are audible. */
        private const val FADE_STEPS_PER_SECOND = 20
        private const val EXTRA_SESSION_MINUTES = "session_minutes"

        /** Alarm-stream routing, so timer alerts are heard over study music. */
        private val ALERT_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

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
