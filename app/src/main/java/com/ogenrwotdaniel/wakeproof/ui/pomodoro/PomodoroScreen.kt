package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusSound
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroPhase
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import com.ogenrwotdaniel.wakeproof.ui.theme.BreakGreen
import java.util.Calendar

/**
 * Pomodoro study mode: 25/5 cycles with a long break every 4th, running in a
 * foreground service. The AI study chat (needs a Kimi key) is deliberately
 * locked during focus phases — that's the point of the app.
 *
 * While a focus block is counting down the screen collapses to [FocusMode]:
 * the timer, and nothing else, until the break. See
 * `WakeProofSettings.focusImmersiveTimer`.
 */
@Composable
fun PomodoroScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PomodoroViewModel = hiltViewModel()
) {
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val thoughts by viewModel.thoughts.collectAsStateWithLifecycle()
    val spotifyError by viewModel.spotifyError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val chatAllowed = viewModel.hasKimiKey && (!timer.active || !timer.running ||
        timer.phase != PomodoroPhase.FOCUS)

    // Same condition MainViewModel uses to hide the tablet's navigation column,
    // evaluated here from the same two sources rather than passed down: this
    // screen already collects both, and a parameter would be a second way for
    // the two halves of one decision to disagree.
    val immersive = settings.focusImmersiveTimer && timer.active && timer.running &&
        timer.phase == PomodoroPhase.FOCUS

    // Keeping the screen awake during focus is owned by MainActivity (window-wide,
    // every screen), not by this composable — see MainViewModel.keepScreenOnActive.

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        if (immersive) {
            FocusMode(
                timer = timer,
                subject = settings.activeSubject,
                onPark = viewModel::parkThought,
                onPause = { PomodoroTimerService.pause(context) },
                onStop = { PomodoroTimerService.stop(context) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Scrolls, because this screen is a stack of eight sections and
                // on a phone the last of them used to be simply unreachable —
                // laid out past the bottom of the window with no way to get to it.
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (timer.active) timer.phase.label else "Pomodoro",
                style = MaterialTheme.typography.headlineMedium
            )
            if (timer.active && timer.targetFocusBlocks > 0) {
                Text(
                    text = "Pomodoro ${
                        (timer.completedFocusSessions + if (timer.phase == PomodoroPhase.FOCUS) 1 else 0)
                            .coerceIn(1, timer.targetFocusBlocks)
                    } of ${timer.targetFocusBlocks}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))

            CountdownRing(timer = timer, size = 220.dp)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { index ->
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = CircleShape,
                        color = if (index < timer.completedFocusSessions % 4 ||
                            (timer.completedFocusSessions > 0 && timer.completedFocusSessions % 4 == 0)
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {}
                }
            }

            Spacer(Modifier.height(16.dp))
            if (!timer.active) {
                SubjectPicker(
                    subjects = settings.studySubjects.sorted(),
                    active = settings.activeSubject,
                    onSelect = viewModel::setActiveSubject,
                    onAdd = viewModel::addSubject,
                    onRemove = viewModel::removeSubject
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    text = "Studying: ${settings.activeSubject}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            if (!timer.active) {
                // Timed sessions auto-loop focus/break cycles and stop themselves;
                // "Free" keeps looping until stopped.
                // FlowRow, not Row: four buttons of these labels do not fit
                // across a phone, and a Row would simply cut the last one off.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SESSION_LENGTHS.forEach { (label, minutes) ->
                        Button(onClick = { PomodoroTimerService.start(context, minutes) }) {
                            Text(label)
                        }
                    }
                    OutlinedButton(onClick = { PomodoroTimerService.start(context) }) {
                        Text("Free")
                    }
                }
                Text(
                    text = "${settings.focusMinutes} min focus · ${settings.shortBreakMinutes} min " +
                        "break · loops automatically. A timed session runs whole " +
                        "pomodoros and stops itself; Free keeps cycling until you stop it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (timer.running) {
                        Button(onClick = { PomodoroTimerService.pause(context) }) { Text("Pause") }
                    } else {
                        Button(onClick = { PomodoroTimerService.resume(context) }) { Text("Resume") }
                    }
                    OutlinedButton(onClick = { PomodoroTimerService.reset(context) }) { Text("Reset") }
                    OutlinedButton(onClick = { PomodoroTimerService.stop(context) }) { Text("Stop") }
                }
            }

            Spacer(Modifier.height(12.dp))
            ThoughtParking(
                thoughts = thoughts,
                onPark = viewModel::parkThought,
                onToggle = viewModel::setThoughtDone,
                onClearDone = viewModel::clearDoneThoughts
            )

            Spacer(Modifier.height(12.dp))
            MusicControls(
                focusEnabled = settings.focusMusicEnabled,
                breakEnabled = settings.breakMusicEnabled,
                soundId = settings.focusSoundId,
                spotifyInstalled = viewModel.spotifyInstalled,
                onFocusChange = viewModel::setMusicEnabled,
                onBreakChange = viewModel::setBreakMusicEnabled,
                onSoundChange = viewModel::setFocusSound
            )

            Spacer(Modifier.height(8.dp))
            SpotifyControls(
                installed = viewModel.spotifyInstalled,
                hasPlaylist = settings.spotifyPlaylistUri.isNotBlank(),
                followsTimer = settings.focusSoundId == FocusSound.SPOTIFY_ID,
                error = spotifyError,
                onOpen = viewModel::openSpotify,
                onPlayPause = viewModel::spotifyPlayPause,
                onNext = viewModel::spotifyNext,
                onPrevious = viewModel::spotifyPrevious
            )
            if (settings.focusLockPhone) {
                Text(
                    text = if (timer.active && timer.running) {
                        "🔒 Phone is pinned to WakeProof until the session ends"
                    } else {
                        "🔒 Phone locks to WakeProof for a whole session, breaks " +
                            "included (Settings)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            if (viewModel.hasKimiKey) {
                StudyChatPanel(
                    messages = chat.messages,
                    sending = chat.sending,
                    error = chat.error,
                    enabled = chatAllowed,
                    onSend = viewModel::sendChat,
                    // A fixed height, not weight(1f): inside a scrolling column
                    // the available height is infinite, so a weight is a crash
                    // and an unbounded LazyColumn would be one too.
                    modifier = Modifier.height(340.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}


/**
 * The screen during a focus block: the countdown, and as close to nothing else
 * as the app can honestly get away with.
 *
 * "Lock phone during focus" already stops you leaving WakeProof. That left the
 * app itself as the last thing to fiddle with mid-block — a study chat, a sound
 * picker, a subject list, five other screens — so a pinned phone became a phone
 * pinned to a menu. This is the other half of that lock.
 *
 * What survives, and why:
 *
 * * **Parking a thought.** The one interaction that exists *because* you are
 *   mid-focus: it is how a distraction gets written down in two taps instead of
 *   being chased. Removing it would push people back to the thing it replaces.
 * * **Pause and stop.** Deliberately plain text, at the bottom, with no colour
 *   drawing the eye — an alarm clock app should make quitting possible and
 *   unappealing, not impossible. A phone call, a person at the door, and a real
 *   emergency all outrank a pomodoro.
 *
 * Everything else comes back by itself at the break.
 */
@Composable
private fun FocusMode(
    timer: PomodoroState,
    subject: String,
    onPark: (String) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var parking by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = subject.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (timer.targetFocusBlocks > 0) {
            Text(
                text = "Pomodoro ${
                    (timer.completedFocusSessions + 1).coerceIn(1, timer.targetFocusBlocks)
                } of ${timer.targetFocusBlocks}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(32.dp))
        CountdownRing(timer = timer, size = 300.dp)
        Spacer(Modifier.height(20.dp))

        Text(
            text = "Break at ${finishTimeLabel(context, timer.remainingSeconds)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(40.dp))
        if (parking) {
            Row(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Park it for later…") },
                    singleLine = true
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        onPark(input)
                        input = ""
                        parking = false
                    },
                    enabled = input.isNotBlank()
                ) { Text("Park") }
            }
        } else {
            TextButton(onClick = { parking = true }) { Text("💭 Park a thought") }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onPause) { Text("Pause") }
            TextButton(onClick = onStop) { Text("End session") }
        }
        Text(
            text = "Everything else is back at the break.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Wall-clock time [remainingSeconds] from now, in the phone's own 12/24-hour
 * format — "break at 14:32" is a far easier thing to hold in your head than
 * "18:04 remaining", and it is the number you compare against the rest of the
 * day.
 */
private fun finishTimeLabel(context: android.content.Context, remainingSeconds: Int): String {
    val end = Calendar.getInstance().apply { add(Calendar.SECOND, remainingSeconds) }
    return formatAlarmTime(context, end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE))
}

/**
 * The countdown itself: a ring that empties as the phase runs down.
 *
 * Shared by the normal screen and [FocusMode] so there is one countdown in the
 * app rather than two that drift apart — the arc's easing, its colours and the
 * "breathing" while running all have to read the same in both.
 *
 * The arc eases between ticks rather than stepping, and pulses gently while
 * running: a timer that is visibly alive is the difference between glancing at
 * it and checking whether it is still going.
 */
@Composable
private fun CountdownRing(
    timer: PomodoroState,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val fraction by animateFloatAsState(
        targetValue = if (timer.totalSeconds == 0) 0f
        else timer.remainingSeconds.toFloat() / timer.totalSeconds,
        animationSpec = tween(durationMillis = 600),
        label = "arcFraction"
    )
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400), RepeatMode.Reverse),
        label = "breathAlpha"
    )
    val arcAlpha = if (timer.active && timer.running) breath else 1f
    // Focus follows the scheme's primary; a break is the app's fixed green.
    // The focus colour used to be a hardcoded teal that matched the palette in
    // theme/Color.kt — which the theme was not actually using.
    val focusColor = MaterialTheme.colorScheme.primary
    val arcColor by animateColorAsState(
        targetValue = if (timer.active && timer.phase != PomodoroPhase.FOCUS) BreakGreen
        else focusColor,
        animationSpec = tween(800),
        label = "arcColor"
    )
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            // Stroke scales with the ring: the full-screen ring drawn with the
            // inline ring's fixed 20 px stroke looks like a hairline.
            val stroke = Stroke(width = this.size.minDimension * 0.045f, cap = StrokeCap.Round)
            drawArc(
                color = trackColor,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = stroke
            )
            drawArc(
                color = arcColor.copy(alpha = arcAlpha),
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = stroke
            )
        }
        Text(
            text = "%d:%02d".format(timer.remainingSeconds / 60, timer.remainingSeconds % 60),
            style = if (size >= 280.dp) MaterialTheme.typography.displayLarge
            else MaterialTheme.typography.displayMedium
        )
    }
}

/**
 * Timed-session lengths offered before a session starts.
 *
 * The service turns minutes into whole focus blocks at the user's own
 * focus/break lengths, so these are targets rather than promises: 90 minutes
 * at 25+5 is three pomodoros, which is the nearest whole number of blocks that
 * fits. 1 h 30 exists because two pomodoros is short for real work and four is
 * more than most sittings — it was the length people were building by hand out
 * of a Free session and a clock.
 */
private val SESSION_LENGTHS = listOf(
    "1 h" to 60,
    "1 h 30" to 90,
    "2 h" to 120
)

/**
 * What-am-I-studying picker: chips for the user's subjects (long-press-free —
 * a "×" appears on the selected chip to remove it) plus inline add. The active
 * subject is stamped onto each completed focus block for per-subject stats.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectPicker(
    subjects: List<String>,
    active: String,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var adding by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subjects.forEach { subject ->
                FilterChip(
                    selected = subject == active,
                    onClick = {
                        if (subject == active && subjects.size > 1) onRemove(subject)
                        else onSelect(subject)
                    },
                    label = {
                        Text(if (subject == active && subjects.size > 1) "$subject ×" else subject)
                    }
                )
            }
            FilterChip(
                selected = false,
                onClick = { adding = !adding },
                label = { Text("+ subject") }
            )
        }
        if (adding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("e.g. Calculus") },
                    singleLine = true
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        onAdd(nameInput)
                        nameInput = ""
                        adding = false
                    },
                    enabled = nameInput.isNotBlank()
                ) { Text("Add") }
            }
        }
    }
}

/**
 * Deep-work "thought parking": dump a distracting thought in two taps and get
 * back to work; deal with the list on a break. Collapsed to one line until
 * tapped.
 */
@Composable
private fun ThoughtParking(
    thoughts: List<com.ogenrwotdaniel.wakeproof.data.db.ParkedThought>,
    onPark: (String) -> Unit,
    onToggle: (com.ogenrwotdaniel.wakeproof.data.db.ParkedThought, Boolean) -> Unit,
    onClearDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val open = thoughts.count { !it.done }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "💭 Parked thoughts" + if (open > 0) " ($open)" else "",
                style = MaterialTheme.typography.bodyLarge
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Open")
            }
        }
        if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Park it for later…") },
                    singleLine = true
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        onPark(input)
                        input = ""
                    },
                    enabled = input.isNotBlank()
                ) { Text("Park") }
            }
            thoughts.take(6).forEach { thought ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = thought.done,
                        onCheckedChange = { onToggle(thought, it) }
                    )
                    Text(
                        text = thought.text,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (thought.done) TextDecoration.LineThrough else null,
                        color = if (thought.done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (thoughts.any { it.done }) {
                TextButton(onClick = onClearDone) { Text("Clear done") }
            }
        }
    }
}

/**
 * Study-sound controls: independent focus/break toggles plus the source picker
 * (shown while either toggle is on). The picker is exclusive — one of the
 * bundled loops *or* Spotify, never both — so choosing Spotify silences the
 * ambient loop and hands the same phase toggles to Spotify instead. Turning
 * both toggles off frees the audio output entirely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicControls(
    focusEnabled: Boolean,
    breakEnabled: Boolean,
    soundId: String,
    spotifyInstalled: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onBreakChange: (Boolean) -> Unit,
    onSoundChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyPicked = soundId == FocusSound.SPOTIFY_ID
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "Study sound", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Sound in focus", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = focusEnabled, onCheckedChange = onFocusChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Sound in breaks", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = breakEnabled, onCheckedChange = onBreakChange)
        }
        if (focusEnabled || breakEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusSound.entries.forEach { sound ->
                    FilterChip(
                        selected = sound.id == soundId,
                        onClick = { onSoundChange(sound.id) },
                        label = { Text(sound.label) }
                    )
                }
                // Spotify sits in the same row as the loops because it is the
                // same choice: whatever is selected here is the only sound.
                // Kept visible when already picked, even if Spotify got removed.
                if (spotifyInstalled || spotifyPicked) {
                    FilterChip(
                        selected = spotifyPicked,
                        onClick = { onSoundChange(FocusSound.SPOTIFY_ID) },
                        label = { Text("🎧 Spotify") }
                    )
                }
            }
            if (spotifyPicked) {
                Text(
                    text = "Spotify is your study sound — no ambient loop plays. Open the " +
                        "playlist once below so Spotify owns playback; after that the timer " +
                        "plays and pauses it for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Seven chips of two words each say nothing about what they
                // sound like, and there is no auditioning a loop without
                // starting a session. One line under the picker is the cheapest
                // honest answer to "which of these do I want?".
                FocusSound.fromId(soundId)?.let { picked ->
                    Text(
                        text = picked.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Spotify hand-off row: one button to jump into the saved playlist (or the app),
 * plus transport keys that work without leaving WakeProof — the useful half when
 * "Lock phone during focus" makes switching apps impossible. Media keys reach
 * whichever app currently owns the media session, so they only hit Spotify while
 * Spotify is the phone's active player.
 */
@Composable
private fun SpotifyControls(
    installed: Boolean,
    hasPlaylist: Boolean,
    followsTimer: Boolean,
    error: String?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onOpen) {
                Text(
                    when {
                        !installed -> "Get Spotify"
                        hasPlaylist -> "🎧 Focus playlist"
                        else -> "🎧 Spotify"
                    }
                )
            }
            if (installed) {
                OutlinedButton(onClick = onPrevious) { Text("⏮") }
                OutlinedButton(onClick = onPlayPause) { Text("⏯") }
                OutlinedButton(onClick = onNext) { Text("⏭") }
            }
        }
        Text(
            text = when {
                !installed -> "Spotify isn't installed on this phone."
                followsTimer -> "Spotify is your study sound: it plays and pauses with the timer."
                hasPlaylist -> "Controls work while the phone is pinned to WakeProof."
                else -> "Set a focus playlist in Settings to open it in one tap."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StudyChatPanel(
    messages: List<ChatMessage>,
    sending: Boolean,
    error: String?,
    enabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (enabled) "AI study coach (break time)"
            else "AI study coach — locked during focus",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { message ->
                Text(
                    text = (if (message.role == "user") "You: " else "Coach: ") + message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.role == "user") MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = enabled && !sending,
                placeholder = { Text("Quiz me / explain…") },
                singleLine = true
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = enabled && !sending && input.isNotBlank()
            ) { Text(if (sending) "…" else "Send") }
        }
    }
}
