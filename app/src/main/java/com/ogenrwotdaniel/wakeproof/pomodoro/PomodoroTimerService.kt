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
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.ogenrwotdaniel.wakeproof.AppForeground
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.data.sync.LiveFocusSession
import com.ogenrwotdaniel.wakeproof.distraction.AppBlockService
import com.ogenrwotdaniel.wakeproof.distraction.WallRulesRepository
import com.ogenrwotdaniel.wakeproof.distraction.essentialPackages
import com.ogenrwotdaniel.wakeproof.wear.WearStatsSync
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val targetFocusBlocks: Int = 0,
    /**
     * This focus block's tree withered: the user left WakeProof for a
     * non-study app for longer than the grace period. The block still runs to
     * the end, but is logged as not counting. Reset at every new focus phase.
     */
    val treeWithered: Boolean = false,
    /** Away in a non-study app right now and the grace period is running. */
    val treeAtRisk: Boolean = false
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
    @Inject lateinit var wearStatsSync: WearStatsSync
    @Inject lateinit var spotify: SpotifyController
    @Inject lateinit var usageStatsRepository: UsageStatsRepository
    @Inject lateinit var wallRules: WallRulesRepository
    @Inject lateinit var liveFocusSession: LiveFocusSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    /**
     * [SystemClock.elapsedRealtime] at which the running phase reaches zero.
     * The countdown is READ from this rather than decremented once per
     * `delay(1_000)`: each delay overshoots by a few ms, so a decrementing
     * timer fell seconds behind real time over a block — and behind the watch,
     * which counts from the absolute finish time it was sent.
     */
    private var phaseDeadline = 0L
    private var focusSeconds = 25 * 60
    private var shortBreakSeconds = 5 * 60
    private var longBreakSeconds = 15 * 60
    /** A long break after every this-many focus blocks; set per session. */
    private var longBreakEvery = 4
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
        // Spotify picked as the study sound: it rides the timer through the very
        // same phase toggles the loops use. Media keys only — playback resumes
        // when the phase wants sound and pauses on break/pause/stop. We never
        // send a pause before having sent a play, so a timer cannot silence
        // music the user started for something else.
        scope.launch {
            combine(state, settingsRepository.settings) { s, cfg ->
                FocusSound.isSpotify(cfg.focusSoundId) && soundWanted(s, cfg)
            }.distinctUntilChanged().collect { shouldPlay ->
                if (shouldPlay == spotifyPlaying) return@collect
                if (spotifyPlaying == null && !shouldPlay) return@collect
                spotifyPlaying = shouldPlay
                if (shouldPlay) spotify.play() else spotify.pause()
            }
        }
        // Forest-style stakes: while a focus block runs, leaving for anything
        // that is not a study app starts a grace period, and staying away past
        // it withers the block's tree. collectLatest cancels the guard the
        // moment the block ends, pauses, or the tree is already lost.
        scope.launch {
            combine(state, settingsRepository.settings) { s, cfg ->
                cfg.deepFocus && s.active && s.running && s.phase == PomodoroPhase.FOCUS &&
                    !s.treeWithered
            }.distinctUntilChanged().collectLatest { guarding ->
                if (guarding) guardTree() else clearTreeWarning()
            }
        }
        // Every change that matters to a restart — a phase, a finished block,
        // a withered tree — is written to disk, so a reboot or a force-stop
        // resumes the session instead of ending it (see recoverSession).
        scope.launch {
            state.map { Triple(it.active, it.phase to it.completedFocusSessions, it.treeWithered) }
                .distinctUntilChanged()
                .collect { if (_state.value.active) persistSession() }
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
                // startForegroundService obliges a startForeground every time,
                // even for a start that is about to be ignored below.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(if (_state.value.active) "Session running" else "Starting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                // A second start would quietly reset a session in progress —
                // a stop by another name.
                if (_state.value.active) return START_NOT_STICKY
                val sessionMinutes = intent.getIntExtra(EXTRA_SESSION_MINUTES, 0)
                scope.launch {
                    val settings = settingsRepository.settings.first()
                    // A "1 h" session with 25+5 settings = 2 focus blocks; "2 h" = 4;
                    // "3 h" is its own fixed recipe. See sessionPlan.
                    val plan = sessionPlan(
                        sessionMinutes = sessionMinutes,
                        focusMinutes = settings.focusMinutes,
                        shortBreakMinutes = settings.shortBreakMinutes,
                        longBreakMinutes = settings.longBreakMinutes
                    )
                    focusSeconds = plan.focusMinutes * 60
                    shortBreakSeconds = plan.shortBreakMinutes * 60
                    longBreakSeconds = plan.longBreakMinutes * 60
                    longBreakEvery = plan.longBreakEvery
                    _state.value = PomodoroState(
                        active = true,
                        running = true,
                        phase = PomodoroPhase.FOCUS,
                        remainingSeconds = focusSeconds,
                        totalSeconds = focusSeconds,
                        targetFocusBlocks = plan.focusBlocks
                    )
                    startTicker()
                    updateNotification()
                    syncWatch()
                    // The focus wall lives in AppBlockService; make sure it is up.
                    AppBlockService.sync(applicationContext)
                    onSessionUnderway()
                }
            }
            ACTION_RESTORE -> {
                createChannel()
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Resuming session…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                if (_state.value.active) return START_NOT_STICKY
                val saved = SessionStore.load(this)
                if (saved == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                scope.launch { resumeSaved(saved) }
            }
            // NUCLEAR FOCUS: once a session starts there is no pause, no reset
            // and no stop, from any caller — this screen, the notification, the
            // floating bubble or the watch. It ends when its last block does.
            // Pause was the two-tap way out of every session and stop the
            // one-tap way; reset is gone altogether. Resume stays, for a
            // session paused by a build from before this rule.
            ACTION_PAUSE -> Unit
            ACTION_RESUME -> {
                _state.update { it.copy(running = true) }
                startTicker()
                updateNotification()
                syncWatch()
            }
            ACTION_STOP -> if (!_state.value.active) stopSelf()
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
        clearTreeWarning()
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
            val player = musicPlayer ?: createLoopPlayer(sound)?.also { musicPlayer = it } ?: return
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

    private fun createLoopPlayer(sound: FocusSound): MediaPlayer? =
        MediaPlayer.create(
            this, sound.res, // seamless ambient loop, generated asset
            MUSIC_AUDIO_ATTRIBUTES,
            0
        )?.apply { isLooping = true }

    /**
     * Watches the foreground app for the length of a focus block. See the
     * collector in [onCreate] for when this runs.
     *
     * Needs usage access; without it the guard simply never fires, which errs
     * towards keeping trees rather than killing them for something unmeasured.
     * The screen being off is never "away" — that is the most focused a phone
     * can be.
     */
    private suspend fun guardTree() {
        val power = getSystemService(PowerManager::class.java)
        val essentials = essentialPackages(this)
        var awaySince: Long? = null
        // foregroundPackage() answers null while the user stays put in one app,
        // so the last real answer is what is on screen — exactly as in
        // AppBlockService. Null until the first resume is seen: unknown is safe.
        var foreground: String? = null
        while (true) {
            delay(TREE_POLL_MS)
            if (!power.isInteractive || !usageStatsRepository.hasAccess()) {
                if (awaySince != null) clearTreeWarning()
                awaySince = null
                continue
            }
            usageStatsRepository.foregroundPackage()?.let { foreground = it }
            val current = foreground
            val allowed = current == null || current in essentials ||
                current in latestSettings.studyApps
            if (allowed) {
                if (awaySince != null) clearTreeWarning()
                awaySince = null
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val since = awaySince ?: now.also {
                awaySince = it
                _state.update { s -> s.copy(treeAtRisk = true) }
                warnTree(usageStatsRepository.appLabel(current!!))
            }
            if (now - since >= TREE_GRACE_MS) {
                _state.update { it.copy(treeWithered = true, treeAtRisk = false) }
                notifyTree(
                    "Your tree withered 🥀",
                    "This focus block won't count. The timer is still running — " +
                        "come back and the next one can grow."
                )
                return
            }
        }
    }

    private fun warnTree(appLabel: String) = notifyTree(
        "Your tree is withering 🌱",
        "Leave $appLabel within ${TREE_GRACE_MS / 1000} s or this focus block won't count."
    )

    private fun notifyTree(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 4,
            Intent(this, com.ogenrwotdaniel.wakeproof.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(NotificationManager::class.java).notify(
            TREE_NOTIFICATION_ID,
            Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun clearTreeWarning() {
        if (_state.value.treeAtRisk) _state.update { it.copy(treeAtRisk = false) }
        // Only the warning is cleared; a "withered" notice is left to be read.
        if (!_state.value.treeWithered) {
            getSystemService(NotificationManager::class.java).cancel(TREE_NOTIFICATION_ID)
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
    private fun maybeFadeOutBeforePhaseEnd(s: PomodoroState, previousRemaining: Int) {
        val fade = latestSettings.soundFadeSeconds
        // A crossing rather than an equality: a late tick can step over a value.
        if (fade <= 0 || previousRemaining <= fade || s.remainingSeconds > fade) return
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

    private fun armDeadline() {
        phaseDeadline = SystemClock.elapsedRealtime() + _state.value.remainingSeconds * 1_000L
    }

    private fun startTicker() {
        tickerJob?.cancel()
        armDeadline()
        tickerJob = scope.launch {
            while (_state.value.running) {
                // Sleep to the next whole-second boundary of the deadline, so the
                // displayed second flips when real time says it should.
                val msLeft = phaseDeadline - SystemClock.elapsedRealtime()
                delay(if (msLeft > 0) (msLeft - 1) % 1_000L + 1 else 0L)
                val current = _state.value
                if (!current.running) break
                val remaining = remainingSecondsAt(phaseDeadline, SystemClock.elapsedRealtime())
                if (remaining <= 0) {
                    onPhaseComplete()
                } else if (remaining != current.remainingSeconds) {
                    _state.update { it.copy(remainingSeconds = remaining) }
                    maybeFadeOutBeforePhaseEnd(_state.value, current.remainingSeconds)
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
                    GoalKind.FOCUS,
                    // A withered block is still recorded, so the forest can show
                    // the dead tree, but SKIPPED keeps it out of focus time.
                    if (current.treeWithered) CompletionStatus.SKIPPED else CompletionStatus.VERIFIED,
                    amount = focusSeconds / 60,
                    subject = settingsRepository.settings.first().activeSubject
                )
            }
            // The watch's focus total otherwise waits for the app's screen to
            // close — and a block run with the phone face-down never opens it.
            runCatching { wearStatsSync.push() }
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
                endSession()
                stopSelf()
                return
            }
            val next = if (done % longBreakEvery == 0) PomodoroPhase.LONG_BREAK
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
            armDeadline()
        } else {
            _state.update {
                it.copy(
                    phase = PomodoroPhase.FOCUS,
                    remainingSeconds = focusSeconds,
                    totalSeconds = focusSeconds,
                    // A fresh block, a fresh seedling.
                    treeWithered = false,
                    treeAtRisk = false
                )
            }
            armDeadline()
        }
        syncWatch()
    }

    /**
     * What every session does once it is running, fresh or recovered: quiet the
     * phone and tell the user's other devices how long the focus wall lasts.
     */
    private suspend fun onSessionUnderway() {
        if (wallRules.current().sessionDnd) SessionDnd.enter(this)
        val s = _state.value
        val remaining = sessionRemainingSeconds(
            phase = s.phase,
            remainingSeconds = s.remainingSeconds,
            completedFocusSessions = s.completedFocusSessions,
            targetFocusBlocks = s.targetFocusBlocks,
            focusSeconds = focusSeconds,
            shortBreakSeconds = shortBreakSeconds,
            longBreakSeconds = longBreakSeconds,
            longBreakEvery = longBreakEvery
        )
        runCatching { liveFocusSession.publish(System.currentTimeMillis() + remaining * 1_000L) }
    }

    /**
     * The session is over for good: nothing left to recover, DND handed back,
     * other devices released. The cloud write outlives [scope], which
     * [onDestroy] is about to cancel.
     */
    private fun endSession() {
        SessionStore.clear(this)
        SessionDnd.exit(this)
        CoroutineScope(Dispatchers.IO).launch { runCatching { liveFocusSession.publish(0L) } }
    }

    private fun persistSession() {
        val s = _state.value
        val msLeft = if (s.running) (phaseDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        else s.remainingSeconds * 1_000L
        SessionStore.save(
            this,
            SavedSession(
                focusSeconds = focusSeconds,
                shortBreakSeconds = shortBreakSeconds,
                longBreakSeconds = longBreakSeconds,
                longBreakEvery = longBreakEvery,
                targetFocusBlocks = s.targetFocusBlocks,
                completedFocusSessions = s.completedFocusSessions,
                phase = s.phase,
                phaseEndsAtMillis = System.currentTimeMillis() + msLeft,
                treeWithered = s.treeWithered
            )
        )
    }

    /** Picks up a session the process lost; see [recoverSession] for the rules. */
    private suspend fun resumeSaved(saved: SavedSession) {
        val now = System.currentTimeMillis()
        val outcome = recoverSession(saved, now)
        if (outcome.witheredBlock) {
            runCatching {
                completionRepository.log(
                    GoalKind.FOCUS,
                    CompletionStatus.SKIPPED,
                    amount = saved.focusSeconds / 60,
                    subject = settingsRepository.settings.first().activeSubject
                )
            }
        }
        val resume = outcome.resume
        if (resume == null) {
            endSession()
            stopSelf()
            return
        }
        focusSeconds = resume.focusSeconds
        shortBreakSeconds = resume.shortBreakSeconds
        longBreakSeconds = resume.longBreakSeconds
        longBreakEvery = resume.longBreakEvery
        val left = ((resume.phaseEndsAtMillis - now + 999) / 1_000).toInt().coerceAtLeast(1)
        _state.value = PomodoroState(
            active = true,
            running = true,
            phase = resume.phase,
            remainingSeconds = left,
            totalSeconds = durationOf(resume.phase),
            completedFocusSessions = resume.completedFocusSessions,
            targetFocusBlocks = resume.targetFocusBlocks,
            treeWithered = resume.treeWithered
        )
        startTicker()
        updateNotification()
        syncWatch()
        AppBlockService.sync(applicationContext)
        onSessionUnderway()
        notifyTree(
            "Session resumed",
            if (outcome.witheredBlock || resume.treeWithered) {
                "Closing WakeProof doesn't end a session — it cost this block's tree."
            } else {
                "Closing WakeProof doesn't end a session."
            }
        )
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
        val finishAt = if (s.running) {
            System.currentTimeMillis() + (phaseDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else 0L
        scope.launch {
            runCatching {
                wearTimerSync.push(s.active, s.running, s.phase.label, s.remainingSeconds, finishAt)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val s = _state.value
        // No Pause or Stop actions: a session has no way out (see ACTION_PAUSE).
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pomodoro — ${s.phase.label}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Marks it as a timer so the paired watch surfaces it as an ongoing
            // activity; not setLocalOnly, so it bridges to the watch.
            .setCategory(Notification.CATEGORY_STOPWATCH)
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

    companion object {
        private val _state = MutableStateFlow(PomodoroState())

        /** Process-wide observable timer state. */
        val state: StateFlow<PomodoroState> = _state.asStateFlow()

        private const val ACTION_START = "com.ogenrwotdaniel.wakeproof.action.POMODORO_START"
        private const val ACTION_PAUSE = "com.ogenrwotdaniel.wakeproof.action.POMODORO_PAUSE"
        private const val ACTION_RESUME = "com.ogenrwotdaniel.wakeproof.action.POMODORO_RESUME"
        private const val ACTION_STOP = "com.ogenrwotdaniel.wakeproof.action.POMODORO_STOP"
        private const val ACTION_RESTORE = "com.ogenrwotdaniel.wakeproof.action.POMODORO_RESTORE"
        private const val CHANNEL_ID = "wakeproof_pomodoro"
        private const val ALERT_CHANNEL_ID = "wakeproof_pomodoro_alerts"
        private const val NOTIFICATION_ID = 2
        private const val SESSION_DONE_NOTIFICATION_ID = 3
        private const val TREE_NOTIFICATION_ID = 5
        private const val TREE_POLL_MS = 2_000L
        /**
         * How long a non-study app may hold the screen before the tree withers.
         * Long enough to dismiss a notification you tapped by accident, short
         * enough that a "quick look" at a feed does not fit inside it.
         */
        private const val TREE_GRACE_MS = 10_000L
        /**
         * Playback level for the bundled loops. Raised from 0.55 when the assets
         * were regenerated at a matched -14 LUFS: the old set averaged louder in
         * the file, so holding 0.55 would have made every sound quieter than the
         * one the user had got used to.
         */
        private const val MUSIC_VOLUME = 0.7f

        private val MUSIC_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /** Ramp resolution. 20 Hz is well under the threshold where steps are audible. */
        private const val FADE_STEPS_PER_SECOND = 20
        private const val EXTRA_SESSION_MINUTES = "session_minutes"

        /** Alarm-stream routing, so timer alerts are heard over study music. */
        private val ALERT_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /**
         * Starts a session of [sessionMinutes] (60, 90, 120 or the fixed
         * [THREE_HOUR_SESSION_MINUTES]); 0 means [DEFAULT_SESSION_MINUTES]. Once
         * started it cannot be paused or stopped.
         */
        fun start(context: Context, sessionMinutes: Int = 0) = context.startForegroundService(
            intent(context, ACTION_START).putExtra(EXTRA_SESSION_MINUTES, sessionMinutes)
        )
        /** Refused while a session is active — kept for the watch's command path. */
        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        /** Refused while a session is active — kept for the watch's command path. */
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        /**
         * Brings back a session the process lost (reboot, force-stop, killed).
         * Cheap and safe to call from every start-up path: does nothing unless a
         * session is saved and none is running.
         */
        fun restore(context: Context) {
            if (_state.value.active || SessionStore.load(context) == null) return
            runCatching { context.startForegroundService(intent(context, ACTION_RESTORE)) }
        }

        /**
         * The walls lost a permission mid-session. The session carries on — it
         * cannot be stopped — but the block being dodged does not count.
         * Returns true when a tree was lost.
         */
        fun breakout(): Boolean {
            val s = _state.value
            if (!s.active || s.phase != PomodoroPhase.FOCUS || s.treeWithered) return false
            _state.update { it.copy(treeWithered = true, treeAtRisk = false) }
            return true
        }

        private fun intent(context: Context, action: String) =
            Intent(context, PomodoroTimerService::class.java).setAction(action)
    }
}
