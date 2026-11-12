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
import com.ogenrwotdaniel.wakeproof.distraction.DefaultApps
import com.ogenrwotdaniel.wakeproof.distraction.essentialPackages
import com.ogenrwotdaniel.wakeproof.distraction.readDefaultApps
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
import kotlinx.coroutines.withContext

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
    @Inject lateinit var recallStore: com.ogenrwotdaniel.wakeproof.study.RecallStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    /**
     * One phase change at a time. A phase ends on the ticker, or early when the
     * device this session started on has already moved on ([realign]); ending it
     * writes to the database, and the other must not end the same phase again
     * while that write is in flight.
     */
    private val phaseLock = kotlinx.coroutines.sync.Mutex()
    /**
     * [SystemClock.elapsedRealtime] at which the running phase reaches zero.
     * The countdown is READ from this rather than decremented once per
     * `delay(1_000)`: each delay overshoots by a few ms, so a decrementing
     * timer fell seconds behind real time over a block — and behind the watch,
     * which counts from the absolute finish time it was sent.
     */
    private var phaseDeadline = 0L
    private var focusSeconds = 25 * 60
    /** The last block's length; shorter only in an exact (calendar-event) session. */
    private var lastFocusSeconds = 25 * 60
    /** The calendar event this session is for, if any — for the end-of-event check-in. */
    private var event: SessionEvent? = null
    private var shortBreakSeconds = 5 * 60
    private var longBreakSeconds = 15 * 60
    /** A long break after every this-many focus blocks; set per session. */
    private var longBreakEvery = 4
    /** This session's id on every device that runs it; see [SharedSession]. */
    private var sessionId = ""
        set(value) {
            field = value
            runningSessionId = value
        }
    /** Where this session started: here ([SharedSession.ORIGIN_SELF]), the watch or another install. */
    private var sessionOrigin = SharedSession.ORIGIN_SELF
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
        // The same session running where it started (the watch, the other phone or
        // tablet) is the one to keep time with: a break skipped on the watch, or
        // a phase its alarm ended late, moves this countdown too.
        scope.launch {
            PeerSessions.current.collect { shared -> if (shared != null) realign(shared) }
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
                val exactMinutes = intent.getIntExtra(EXTRA_EXACT_MINUTES, 0)
                val startedFor = intent.getStringExtra(EXTRA_EVENT_KEY)?.let { key ->
                    SessionEvent(key, intent.getStringExtra(EXTRA_EVENT_TITLE).orEmpty())
                }
                scope.launch {
                    // A session already running on the watch or another device is
                    // joined, not doubled: one session, the same countdown everywhere.
                    PeerSessions.joinable()?.let { running ->
                        join(running)
                        return@launch
                    }
                    val settings = settingsRepository.settings.first()
                    // A "1 h" session with 25+5 settings = 2 focus blocks; "2 h" = 4;
                    // "3 h" is its own fixed recipe. See sessionPlan. A calendar
                    // event's session lasts exactly as long as the event.
                    val plan = if (exactMinutes > 0) exactSessionPlan(exactMinutes) else sessionPlan(
                        sessionMinutes = sessionMinutes,
                        focusMinutes = settings.focusMinutes,
                        shortBreakMinutes = settings.shortBreakMinutes,
                        longBreakMinutes = settings.longBreakMinutes
                    )
                    focusSeconds = plan.focusMinutes * 60
                    lastFocusSeconds = plan.lastFocusMinutes * 60
                    shortBreakSeconds = plan.shortBreakMinutes * 60
                    longBreakSeconds = plan.longBreakMinutes * 60
                    longBreakEvery = plan.longBreakEvery
                    sessionId = java.util.UUID.randomUUID().toString()
                    sessionOrigin = SharedSession.ORIGIN_SELF
                    event = startedFor
                    SessionEventStore.save(this@PomodoroTimerService, startedFor)
                    val first = blockSeconds(1, plan.focusBlocks)
                    _state.value = PomodoroState(
                        active = true,
                        running = true,
                        phase = PomodoroPhase.FOCUS,
                        remainingSeconds = first,
                        totalSeconds = first,
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
            ACTION_JOIN -> {
                createChannel()
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(if (_state.value.active) "Session running" else "Joining…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                val shared = decodeShared(intent.getStringExtra(EXTRA_SHARED))
                // Never over a different session: that would be a stop by another name.
                if (shared == null || (_state.value.active && shared.id != sessionId)) {
                    if (!_state.value.active) stopSelf()
                    return START_NOT_STICKY
                }
                scope.launch { join(shared) }
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
        // The session-complete chime starts just before stopSelf; releasing it
        // here would cut it off. It releases itself on completion or error, and
        // this late release (a no-op after those) catches one that never got there.
        chimePlayer?.let { player ->
            android.os.Handler(mainLooper).postDelayed({ runCatching { player.release() } }, CHIME_RELEASE_MS)
        }
        chimePlayer = null
        _state.value = PomodoroState()
        runningSessionId = ""
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
        var ownReading: DefaultApps? = null
        var ownReadAt = 0L
        var ownFallback: Set<String>? = null
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
            // Read each time, so a default changed mid-block counts. Unknown while the
            // walls take their first readings: a blank one has no home screen in it,
            // and the home screen would count as away.
            val essentials = if (AppBlockService.defaultsPending()) {
                null
            } else {
                AppBlockService.defaultApps()?.essential ?: run {
                    // The service is down. Off the main thread, holding the last answer
                    // over a hiccup that would leave the home screen out.
                    val previous = ownReading
                    val readAt = SystemClock.elapsedRealtime()
                    // Throttled whether or not the read worked: a failing PackageManager
                    // must not be asked again on every 2 s poll.
                    if (ownReadAt == 0L || readAt - ownReadAt >= TREE_DEFAULTS_READ_MS) {
                        ownReadAt = readAt
                        ownReading = withContext(Dispatchers.Default) {
                            readDefaultApps(this@PomodoroTimerService, previous)
                        } ?: previous
                        // Never read at all: the plain lookup, as before, so the tree is still judged.
                        if (ownReading == null) {
                            ownFallback = withContext(Dispatchers.Default) { essentialPackages(this@PomodoroTimerService) }
                        }
                    }
                    ownReading?.essential ?: ownFallback
                }
            }
            // Unknown decides nothing, so a running away timer is not reset by it either.
            if (essentials == null) continue
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

    /** [sound] is the raw tone to play, or null for none; tree news gets the low warning note. */
    private fun notifyTree(title: String, text: String, sound: Int? = R.raw.tree_warning) {
        sound?.let(::chime)
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

    /** [rearm] false when [phaseDeadline] is already set exactly ([runFrom]). */
    private fun startTicker(rearm: Boolean = true) {
        tickerJob?.cancel()
        if (rearm) armDeadline()
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
                    phaseLock.lock()
                    try {
                        // Looked at again: a realign may have ended this phase while this waited.
                        if (_state.value.running &&
                            remainingSecondsAt(phaseDeadline, SystemClock.elapsedRealtime()) <= 0
                        ) {
                            onPhaseComplete()
                        }
                    } finally {
                        phaseLock.unlock()
                    }
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
        // The session-complete notification's channel is silent; this is its sound too.
        chime()
        if (current.phase == PomodoroPhase.FOCUS) {
            runCatching {
                logBlock(
                    block = current.completedFocusSessions + 1,
                    // A withered block is still recorded, so the forest can show
                    // the dead tree, but SKIPPED keeps it out of focus time.
                    status = if (current.treeWithered) CompletionStatus.SKIPPED else CompletionStatus.VERIFIED,
                    minutes = blockSeconds(current.completedFocusSessions + 1, current.targetFocusBlocks) / 60
                )
            }
            // A block that grew its tree is worth recalling; the timer screen asks.
            if (!current.treeWithered) {
                runCatching {
                    recallStore.ask(
                        settingsRepository.settings.first().activeSubject,
                        java.time.LocalDate.now().toEpochDay()
                    )
                }
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
                event?.let { com.ogenrwotdaniel.wakeproof.agenda.EventReminders.postCheckIn(this, it) }
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
            val length = blockSeconds(current.completedFocusSessions + 1, current.targetFocusBlocks)
            _state.update {
                it.copy(
                    phase = PomodoroPhase.FOCUS,
                    remainingSeconds = length,
                    totalSeconds = length,
                    // A fresh block, a fresh seedling.
                    treeWithered = false,
                    treeAtRisk = false
                )
            }
            armDeadline()
        }
        syncWatch()
        publishSession()
    }

    /**
     * Records focus block [block]. Under the session's shared id when it has
     * one, so the same block finished on the watch or the tablet too is one row.
     */
    private suspend fun logBlock(block: Int, status: CompletionStatus, minutes: Int) {
        val subject = settingsRepository.settings.first().activeSubject
        if (sessionId.isBlank()) {
            completionRepository.log(GoalKind.FOCUS, status, amount = minutes, subject = subject)
        } else {
            completionRepository.logFocusBlock(sharedBlockId(sessionId, block), status, minutes, subject)
        }
    }

    /** This session as the other devices need it; null for one saved by a build without ids. */
    private fun sharedNow(): SharedSession? {
        val s = _state.value
        val id = validSessionId(sessionId) ?: return null
        if (!s.active || !s.running) return null
        return SharedSession(
            id = id,
            origin = sessionOrigin,
            phase = s.phase,
            phaseEndsAtMillis = System.currentTimeMillis() +
                (phaseDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
            completedFocus = s.completedFocusSessions,
            targetFocusBlocks = s.targetFocusBlocks,
            focusSeconds = focusSeconds,
            shortBreakSeconds = shortBreakSeconds,
            longBreakSeconds = longBreakSeconds,
            longBreakEvery = longBreakEvery,
            lastFocusSeconds = lastFocusSeconds,
            subject = latestSettings.activeSubject
        )
    }

    /** Tells the user's other devices where the session is and how long the focus wall lasts. */
    private fun publishSession() {
        val s = _state.value
        if (!s.active) return
        val remaining = sessionRemainingSeconds(
            phase = s.phase,
            remainingSeconds = s.remainingSeconds,
            completedFocusSessions = s.completedFocusSessions,
            targetFocusBlocks = s.targetFocusBlocks,
            focusSeconds = focusSeconds,
            shortBreakSeconds = shortBreakSeconds,
            longBreakSeconds = longBreakSeconds,
            longBreakEvery = longBreakEvery,
            lastFocusSeconds = lastFocusSeconds
        )
        val endsAt = System.currentTimeMillis() + remaining * 1_000L
        val shared = sharedNow()
        // Never awaited: the write waits for the server, and an offline phone must
        // not hold the ticker (or a join) at a phase end until the network returns.
        scope.launch { runCatching { liveFocusSession.publish(endsAt, shared) } }
    }

    /**
     * Runs [shared], a session another device is running, from where it is now:
     * the same phase, the same phase end. Called again for a session already
     * joined, it only moves the countdown ([realignTo]).
     */
    private suspend fun join(shared: SharedSession) {
        if (_state.value.active) {
            realign(shared)
            return
        }
        val now = System.currentTimeMillis()
        if (!shared.joinableAt(now)) {
            stopSelf()
            return
        }
        event = null
        SessionEventStore.save(this, null)
        runFrom(shared.toSaved(), now)
        // "Focus on your watch" gives way to this device's own timer notification.
        com.ogenrwotdaniel.wakeproof.wear.WatchSessionNotifier.refresh(applicationContext)
        AppBlockService.sync(applicationContext)
        onSessionUnderway()
    }

    /** Moves this countdown to [shared]'s when [realignTo] says so; a no-op otherwise. */
    private suspend fun realign(shared: SharedSession) {
        phaseLock.lock()
        try {
            val s = _state.value
            if (!s.active || !s.running) return
            val now = System.currentTimeMillis()
            val position = sessionPosition(s.phase, s.completedFocusSessions)
            val endsAt = now + (phaseDeadline - SystemClock.elapsedRealtime())
            if (!realignTo(shared, sessionId, position, endsAt, now)) return
            // The origin is one phase on (its clock a moment ahead, or a break skipped
            // on the watch): this phase is ended here as it always is, so its block is
            // recorded with this device's tree and the change is heard. Jumping over it
            // would lose a withered tree.
            if (shared.position == position + 1) {
                onPhaseComplete()
                // The session's last block: it is over, and the state only clears in onDestroy.
                if (sessionId.isBlank()) return
            } else if (shared.position > position && s.phase == PomodoroPhase.FOCUS && s.treeWithered) {
                // Further on than that (copies missed): the block left behind is not run
                // to its end here, but a tree withered in it still spoils it.
                val block = s.completedFocusSessions + 1
                runCatching {
                    logBlock(block, CompletionStatus.SKIPPED, blockSeconds(block, s.targetFocusBlocks) / 60)
                }
            }
            val after = _state.value
            val samePhase = sessionPosition(after.phase, after.completedFocusSessions) == shared.position
            // The same block keeps its tree, alive or withered; another starts fresh.
            runFrom(
                shared.toSaved().copy(treeWithered = after.treeWithered && samePhase),
                System.currentTimeMillis()
            )
        } finally {
            phaseLock.unlock()
        }
    }

    /** Puts the timer at [session]'s phase and phase end, and starts counting. */
    private fun runFrom(session: SavedSession, now: Long) {
        focusSeconds = session.focusSeconds
        lastFocusSeconds = session.lastFocusSeconds
        shortBreakSeconds = session.shortBreakSeconds
        longBreakSeconds = session.longBreakSeconds
        longBreakEvery = session.longBreakEvery
        sessionId = session.sessionId
        sessionOrigin = session.origin
        val msLeft = (session.phaseEndsAtMillis - now).coerceAtLeast(1L)
        val left = ((msLeft + 999) / 1_000).toInt()
        // To the millisecond, not the whole second: two devices on one session
        // should flip their seconds together. Before the state: the collector
        // that writes the session to disk reads it.
        phaseDeadline = SystemClock.elapsedRealtime() + msLeft
        _state.value = PomodoroState(
            active = true,
            running = true,
            phase = session.phase,
            remainingSeconds = left,
            totalSeconds = if (session.phase == PomodoroPhase.FOCUS) {
                focusSecondsForBlock(
                    session.completedFocusSessions + 1, session.targetFocusBlocks, focusSeconds, lastFocusSeconds
                )
            } else if (session.phase == PomodoroPhase.LONG_BREAK) {
                longBreakSeconds
            } else {
                shortBreakSeconds
            },
            completedFocusSessions = session.completedFocusSessions,
            targetFocusBlocks = session.targetFocusBlocks,
            treeWithered = session.treeWithered
        )
        startTicker(rearm = false)
        // A realign inside one phase changes nothing the saving collector watches.
        persistSession()
        updateNotification()
        syncWatch()
    }

    /**
     * What every session does once it is running, fresh or recovered: quiet the
     * phone and tell the user's other devices how long the focus wall lasts.
     */
    private suspend fun onSessionUnderway() {
        if (wallRules.current().sessionDnd) SessionDnd.enter(this)
        publishSession()
    }

    /**
     * The session is over for good: nothing left to recover, DND handed back,
     * other devices released. The cloud write outlives [scope], which
     * [onDestroy] is about to cancel.
     */
    private fun endSession() {
        SessionStore.clear(this)
        SessionEventStore.save(this, null)
        event = null
        SessionDnd.exit(this)
        sessionId = ""
        sessionOrigin = SharedSession.ORIGIN_SELF
        CoroutineScope(Dispatchers.IO).launch { runCatching { liveFocusSession.publish(0L, null) } }
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
                treeWithered = s.treeWithered,
                lastFocusSeconds = lastFocusSeconds,
                sessionId = sessionId,
                origin = sessionOrigin
            )
        )
    }

    /** Picks up a session the process lost; see [recoverSession] for the rules. */
    private suspend fun resumeSaved(saved: SavedSession) {
        val now = System.currentTimeMillis()
        val outcome = recoverSession(saved, now)
        if (outcome.witheredBlock) {
            // Before [runFrom] sets it: the block is named by the session it was part of.
            sessionId = saved.sessionId
            runCatching {
                logBlock(
                    block = saved.completedFocusSessions + 1,
                    status = CompletionStatus.SKIPPED,
                    minutes = focusSecondsForBlock(
                        saved.completedFocusSessions + 1, saved.targetFocusBlocks,
                        saved.focusSeconds, saved.lastFocusSeconds
                    ) / 60
                )
            }
        }
        val resume = outcome.resume
        if (resume == null) {
            endSession()
            stopSelf()
            return
        }
        event = SessionEventStore.load(this)
        runFrom(resume, now)
        AppBlockService.sync(applicationContext)
        onSessionUnderway()
        // Only news is told: the timer screen already shows a session running on.
        if (outcome.witheredBlock || resume.treeWithered) {
            notifyTree(
                "Session resumed",
                "Closing WakeProof doesn't end a session — it cost this block's tree.",
                // Often after a reboot in the night: a notice, not a reason to sound
                // the alarm stream through Do Not Disturb.
                sound = null
            )
        }
    }

    /** Focus block [block]'s length (1-based); see [focusSecondsForBlock]. */
    private fun blockSeconds(block: Int, target: Int) =
        focusSecondsForBlock(block, target, focusSeconds, lastFocusSeconds)

    /** Break lengths; a FOCUS phase asks [blockSeconds] instead, which knows the trimmed last block. */
    private fun durationOf(phase: PomodoroPhase) = when (phase) {
        PomodoroPhase.FOCUS -> blockSeconds(_state.value.completedFocusSessions + 1, _state.value.targetFocusBlocks)
        PomodoroPhase.SHORT_BREAK -> shortBreakSeconds
        PomodoroPhase.LONG_BREAK -> longBreakSeconds
    }

    /**
     * Phase-transition alert. It plays the bundled soft two-note chime
     * (`res/raw/focus_chime`, `tools/sounds/chime.py`) at [CHIME_VOLUME]. It used to
     * be the phone's default notification tone at full volume, which was loud and
     * frightening at the end of every block. It stays on the ALARM stream so session
     * Do Not Disturb and a muted notification stream cannot swallow it, and a
     * transition you miss defeats the timer. The vibration below is the part that
     * carries across the room.
     */
    private fun chime(tone: Int = R.raw.focus_chime) {
        // One still playing is left to finish (it releases itself), so a tree
        // notice right after a transition does not cut the transition's chime off.
        var fresh: MediaPlayer? = null
        runCatching {
            MediaPlayer().also { player ->
                fresh = player
                chimePlayer = player
                player.setAudioAttributes(ALERT_AUDIO_ATTRIBUTES)
                resources.openRawResourceFd(tone).use { fd ->
                    player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                player.setVolume(CHIME_VOLUME, CHIME_VOLUME)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { done ->
                    done.release()
                    if (chimePlayer === done) chimePlayer = null
                }
                player.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (chimePlayer === failed) chimePlayer = null
                    fallbackTone()
                    true // never crash
                }
                player.prepareAsync()
            }
        }.onFailure {
            // Only the player this call made: an earlier chime still playing is left alone.
            fresh?.release()
            if (chimePlayer === fresh) chimePlayer = null
            fallbackTone()
        }
        // Two firm pulses at full amplitude: one soft buzz goes unnoticed in a pocket.
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 400, 200, 400), intArrayOf(0, 255, 0, 255), -1
            ),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    }

    /**
     * The phone's own notification tone, for when the bundled chime cannot play.
     * On the notification stream, not the alarm one, so it is the ordinary level
     * the user already lives with: a transition must still be heard, and the
     * channel itself is silent.
     */
    private fun fallbackTone() {
        runCatching {
            android.media.RingtoneManager.getRingtone(
                this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            )?.play()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Pomodoro timer", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live Pomodoro countdown" }
        )
        manager.deleteNotificationChannel(LEGACY_ALERT_CHANNEL_ID)
        // Separate channel for the end of a session and tree warnings: heads-up
        // importance so they show over other apps; the sound is [chime]'s.
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID, "Pomodoro alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Session-complete alerts"
                // Silent channel: the service plays [chime] itself, so the
                // last block and tree warnings get the same soft level as every
                // other transition instead of the alarm stream at full scale.
                setSound(null, null)
                enableVibration(false) // [chime] vibrates too; two would overlap
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
        // The watch tells its own session by the install id it was started on.
        val shared = sharedNow()
        scope.launch {
            runCatching {
                wearTimerSync.push(
                    s.active, s.running, s.phase.label, s.remainingSeconds, finishAt,
                    shared?.let { if (it.origin == SharedSession.ORIGIN_SELF) it.copy(origin = ORIGIN_PHONE) else it }
                )
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
        private const val ACTION_JOIN = "com.ogenrwotdaniel.wakeproof.action.POMODORO_JOIN"
        private const val EXTRA_SHARED = "shared_session"
        /**
         * What the watch is told a session started on this phone came from. Not
         * the install id: the watch only needs "not mine". A watch that joins
         * republishes it as the origin, so no reader takes that copy for the
         * origin's own ([SharedSession.fromOrigin]) and none realigns to it.
         */
        const val ORIGIN_PHONE = "phone"
        private const val CHANNEL_ID = "wakeproof_pomodoro"
        /**
         * A channel's sound cannot be changed once created, so the soft chime
         * needed a new id; [LEGACY_ALERT_CHANNEL_ID] (the loud default tone) is deleted.
         */
        private const val ALERT_CHANNEL_ID = "wakeproof_pomodoro_alerts_soft"
        private const val LEGACY_ALERT_CHANNEL_ID = "wakeproof_pomodoro_alerts"
        /**
         * Player level for the transition chime on the alarm stream. That stream
         * usually sits near the top, so full scale was a jolt; this is clearly
         * audible across a desk without startling.
         */
        private const val CHIME_VOLUME = 0.35f
        /** Longer than the 2.6 s chime, with room for a slow prepare. */
        private const val CHIME_RELEASE_MS = 10_000L
        private const val NOTIFICATION_ID = 2
        private const val SESSION_DONE_NOTIFICATION_ID = 3
        private const val TREE_NOTIFICATION_ID = 5
        private const val TREE_POLL_MS = 2_000L
        private const val TREE_DEFAULTS_READ_MS = 15_000L
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
        private const val EXTRA_EXACT_MINUTES = "exact_minutes"
        private const val EXTRA_EVENT_KEY = "event_key"
        private const val EXTRA_EVENT_TITLE = "event_title"

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
        /**
         * Starts a session lasting exactly [minutes] for a calendar event
         * (2026-11-01; see [exactSessionPlan]). As nuclear as any other: no
         * pause, no stop. Ignored, like every start, while a session runs.
         */
        fun startForEvent(context: Context, minutes: Int, eventKey: String, eventTitle: String) =
            context.startForegroundService(
                intent(context, ACTION_START)
                    .putExtra(EXTRA_EXACT_MINUTES, minutes)
                    .putExtra(EXTRA_EVENT_KEY, eventKey)
                    .putExtra(EXTRA_EVENT_TITLE, eventTitle)
            )

        /**
         * Runs [shared], a session already running on the watch or another
         * device, here too: the same countdown, with this device's ring, sound
         * and tree. Ignored while a different session runs here.
         */
        fun join(context: Context, shared: SharedSession) = context.startForegroundService(joinIntent(context, shared))

        /** [join] as an intent, for a notification action. */
        fun joinIntent(context: Context, shared: SharedSession): Intent =
            intent(context, ACTION_JOIN).putExtra(EXTRA_SHARED, encodeShared(shared))

        /** The id of the session running here, or blank; a peer's copy of it is not offered to join. */
        @Volatile var runningSessionId: String = ""
            private set

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
            if (_state.value.active) return
            if (SessionStore.load(context) == null) {
                releaseStaleOnce(context)
                return
            }
            runCatching { context.startForegroundService(intent(context, ACTION_RESTORE)) }
        }

        private val staleReleased = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * No session saved and none running (2026-11-04): if the process died
         * mid-session, the watch still mirrors it and the tablet is still walled
         * by it, since the end was never announced. Once per process, withdraw
         * both — the watch's timer item and our own live/focusSession doc.
         */
        private fun releaseStaleOnce(context: Context) {
            if (!staleReleased.compareAndSet(false, true)) return
            val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext, StaleSessionEntryPoint::class.java
            )
            CoroutineScope(Dispatchers.IO).launch {
                if (_state.value.active) return@launch
                runCatching { entry.wearTimerSync().clear() }
                runCatching { entry.liveFocusSession().releaseIfOwnStale() }
            }
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

/** What [PomodoroTimerService.restore] needs outside an injected service. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface StaleSessionEntryPoint {
    fun wearTimerSync(): WearTimerSync
    fun liveFocusSession(): LiveFocusSession
}
