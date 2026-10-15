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
import android.media.MediaMetadataRetriever
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
import com.ogenrwotdaniel.wakeproof.distraction.essentialPackages
import com.ogenrwotdaniel.wakeproof.music.MusicLibrary
import com.ogenrwotdaniel.wakeproof.music.MusicSelection
import com.ogenrwotdaniel.wakeproof.music.Track
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

/** What the user's own music is playing, for the Pomodoro screen. */
data class NowPlaying(val title: String, val artist: String)

/** The one sound source in play: a bundled loop, or the user's own music. */
private sealed interface SoundSource {
    data class Loop(val sound: FocusSound) : SoundSource
    data class Library(val selection: String, val shuffle: Boolean) : SoundSource
}

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
    @Inject lateinit var musicLibrary: MusicLibrary
    @Inject lateinit var usageStatsRepository: UsageStatsRepository

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
    private var musicPlayer: MediaPlayer? = null
    private var currentSource: SoundSource? = null
    /** The user's music, in play order, while [currentSource] is a library. */
    private var queue: List<Track> = emptyList()
    private var queueIndex = 0
    /** Volume ramp in flight; cancelled whenever the target volume changes. */
    private var fadeJob: Job? = null
    /** MediaPlayer has no getVolume, so the ramp tracks where it left off. */
    private var musicVolume = 0f
    /** Latest settings, so the ticker can consult them without suspending. */
    private var latestSettings = WakeProofSettings()
    /** Short-lived player for the transition chime; released on completion. */
    private var chimePlayer: MediaPlayer? = null
    private val overlay by lazy { FloatingTimerOverlay(this) }

    override fun onCreate() {
        super.onCreate()
        // Sound rides the timer: focus and break phases each have their own
        // toggle, and switching the source (a loop, or the user's own music)
        // swaps it live.
        scope.launch { settingsRepository.settings.collect { latestSettings = it } }
        scope.launch {
            combine(state, settingsRepository.settings) { s, cfg ->
                val source: SoundSource = FocusSound.fromId(cfg.focusSoundId)
                    ?.let { SoundSource.Loop(it) }
                    ?: SoundSource.Library(cfg.musicSelection, cfg.musicShuffle)
                Triple(soundWanted(s, cfg), source, cfg.soundFadeSeconds)
            }.distinctUntilChanged().collect { (shouldPlay, source, fade) ->
                if (source != currentSource) {
                    fadeJob?.cancel()
                    musicPlayer?.release()
                    musicPlayer = null
                    musicVolume = 0f
                    queue = emptyList()
                    _nowPlaying.value = null
                    currentSource = source
                }
                if (shouldPlay) startMusic(source, fade) else pauseMusic(fade)
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
     * own toggle, and a paused or stopped timer is always silent.
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
                        totalSeconds = focusSeconds,
                        treeWithered = false,
                        treeAtRisk = false
                    )
                }
                updateNotification()
                syncWatch()
            }
            ACTION_STOP -> stopSelf()
            ACTION_MUSIC_NEXT -> skipTrack(+1)
            ACTION_MUSIC_PREVIOUS -> skipTrack(-1)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        // Best-effort: dismiss the watch timer chip. Detached from [scope] since
        // that is cancelled immediately below.
        CoroutineScope(Dispatchers.IO).launch { runCatching { wearTimerSync.clear() } }
        scope.cancel()
        _nowPlaying.value = null
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
    private fun peakVolumeOf(source: SoundSource) = when (source) {
        is SoundSource.Loop -> MUSIC_VOLUME
        // Commercial masters already sit at a loud, consistent level; the media
        // volume rocker is the user's own control over it.
        is SoundSource.Library -> LIBRARY_VOLUME
    }

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

    private suspend fun startMusic(source: SoundSource, fadeSeconds: Int) {
        runCatching {
            val player = musicPlayer ?: when (source) {
                is SoundSource.Loop -> createLoopPlayer(source.sound)
                is SoundSource.Library -> createLibraryPlayer(source)
            }?.also { musicPlayer = it } ?: return
            val peak = peakVolumeOf(source)
            fadeJob?.cancel()
            if (!player.isPlaying) {
                // Start from silence so the sound arrives under you rather than
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
     * One MediaPlayer for the whole queue, reset and re-pointed at each song,
     * rather than one player per song: the fade ramps hold a reference to the
     * player, so swapping instances mid-fade would leave the ramp turning the
     * volume of a released player while the new song played at the old level.
     */
    private suspend fun createLibraryPlayer(source: SoundSource.Library): MediaPlayer? {
        val tracks = musicLibrary.tracksFor(MusicSelection.decode(source.selection))
        if (tracks.isEmpty()) return null
        queue = if (source.shuffle) tracks.shuffled() else tracks
        val player = MediaPlayer()
        player.setOnCompletionListener { if (loadTrack(it, queueIndex + 1)) it.start() }
        // A file that will not play is skipped, not fatal. Returning true stops
        // MediaPlayer from also firing onCompletion for the broken one.
        player.setOnErrorListener { mp, _, _ ->
            if (loadTrack(mp, queueIndex + 1)) mp.start()
            true
        }
        if (!loadTrack(player, 0)) {
            player.release()
            return null
        }
        return player
    }

    /**
     * Points [player] at the queue entry [index] (wrapping), skipping any file
     * that will not prepare. Returns false only when no song in the queue plays.
     */
    private fun loadTrack(player: MediaPlayer, index: Int): Boolean {
        if (queue.isEmpty()) return false
        repeat(queue.size) { attempt ->
            val i = Math.floorMod(index + attempt, queue.size)
            val prepared = runCatching {
                player.reset()
                player.setAudioAttributes(MUSIC_AUDIO_ATTRIBUTES)
                player.setDataSource(this, queue[i].uri)
                // Local files prepare in milliseconds; prepareAsync would only
                // add a state machine to get the same answer.
                player.prepare()
            }.isSuccess
            if (prepared) {
                queueIndex = i
                // reset() drops the volume back to full; carry the fade's level.
                player.setVolume(musicVolume, musicVolume)
                _nowPlaying.value = nowPlayingFor(queue[i])
                return true
            }
        }
        _nowPlaying.value = null
        return false
    }

    /**
     * A song from a picked folder only knows its file name until it plays; read
     * its tags now, one file at a time, which costs milliseconds.
     */
    private fun nowPlayingFor(track: Track): NowPlaying {
        if (track.documentUri == null) return NowPlaying(track.title, track.artist)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, track.uri)
            NowPlaying(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() } ?: track.title,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() } ?: ""
            )
        } catch (_: Exception) {
            NowPlaying(track.title, "")
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** ⏭ / ⏮ for the user's music. ⏮ past the first few seconds restarts the song. */
    private fun skipTrack(delta: Int) {
        val player = musicPlayer ?: return
        if (currentSource !is SoundSource.Library || queue.isEmpty()) return
        val wasPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        if (delta < 0 && runCatching { player.currentPosition }.getOrDefault(0) > RESTART_THRESHOLD_MS) {
            runCatching { player.seekTo(0) }
            return
        }
        if (loadTrack(player, queueIndex + delta) && wasPlaying) runCatching { player.start() }
    }

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

        private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)

        /** The song playing from the user's own library; null when a loop or nothing plays. */
        val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

        private const val ACTION_START = "com.ogenrwotdaniel.wakeproof.action.POMODORO_START"
        private const val ACTION_PAUSE = "com.ogenrwotdaniel.wakeproof.action.POMODORO_PAUSE"
        private const val ACTION_RESUME = "com.ogenrwotdaniel.wakeproof.action.POMODORO_RESUME"
        private const val ACTION_RESET = "com.ogenrwotdaniel.wakeproof.action.POMODORO_RESET"
        private const val ACTION_STOP = "com.ogenrwotdaniel.wakeproof.action.POMODORO_STOP"
        private const val ACTION_MUSIC_NEXT = "com.ogenrwotdaniel.wakeproof.action.MUSIC_NEXT"
        private const val ACTION_MUSIC_PREVIOUS = "com.ogenrwotdaniel.wakeproof.action.MUSIC_PREVIOUS"
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
        private const val RESTART_THRESHOLD_MS = 3_000
        private const val SESSIONS_PER_LONG_BREAK = 4
        /**
         * Playback level for the bundled loops. Raised from 0.55 when the assets
         * were regenerated at a matched -14 LUFS: the old set averaged louder in
         * the file, so holding 0.55 would have made every sound quieter than the
         * one the user had got used to.
         */
        private const val MUSIC_VOLUME = 0.7f
        private const val LIBRARY_VOLUME = 0.85f

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

        /** [sessionMinutes] > 0 runs a timed session (e.g. 60 or 120); 0 loops forever. */
        fun start(context: Context, sessionMinutes: Int = 0) = context.startForegroundService(
            intent(context, ACTION_START).putExtra(EXTRA_SESSION_MINUTES, sessionMinutes)
        )
        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        fun reset(context: Context) = context.startService(intent(context, ACTION_RESET))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))
        fun nextTrack(context: Context) = context.startService(intent(context, ACTION_MUSIC_NEXT))
        fun previousTrack(context: Context) =
            context.startService(intent(context, ACTION_MUSIC_PREVIOUS))

        private fun intent(context: Context, action: String) =
            Intent(context, PomodoroTimerService::class.java).setAction(action)
    }
}
