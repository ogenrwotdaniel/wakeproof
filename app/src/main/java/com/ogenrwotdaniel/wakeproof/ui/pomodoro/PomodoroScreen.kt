package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import androidx.compose.runtime.saveable.rememberSaveable
import com.ogenrwotdaniel.wakeproof.study.recallDue
import com.ogenrwotdaniel.wakeproof.study.summaryDue
import com.ogenrwotdaniel.wakeproof.study.summaryWriteLeftMillis
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusQuote
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusQuotes
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusSound
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusTree
import androidx.compose.ui.text.font.FontStyle
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroPhase
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.THREE_HOUR_SESSION_MINUTES
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import com.ogenrwotdaniel.wakeproof.ui.theme.BreakGreen
import java.util.Calendar

/**
 * Pomodoro study mode: 25/5 cycles with a long break every 4th, running in a
 * foreground service. The Google Gemini study chat (Coach or Socratic Referee)
 * is deliberately locked during focus phases — that's the point of the app.
 *
 * While a focus block is counting down the screen collapses to [FocusMode]:
 * the timer, and nothing else, until the break. See
 * `WakeProofSettings.focusImmersiveTimer`.
 */
@Composable
fun PomodoroScreen(
    onBack: () -> Unit,
    onPhotographSummary: () -> Unit,
    onOpenSummaries: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PomodoroViewModel = hiltViewModel()
) {
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val thoughts by viewModel.thoughts.collectAsStateWithLifecycle()
    val activeConjecture by viewModel.activeConjecture.collectAsStateWithLifecycle()
    val quote = remember(timer.completedFocusSessions) {
        FocusQuotes.forBlock(java.time.LocalDate.now().toEpochDay(), timer.completedFocusSessions)
    }
    val context = LocalContext.current
    val summaryState by viewModel.summary.collectAsStateWithLifecycle()
    val summaryNow = rememberSummaryNow(summaryState)
    // Read once: the boot count cannot change while the app runs, and a
    // settings lookup on every tick of the countdown would be waste.
    val bootCount = remember { com.ogenrwotdaniel.wakeproof.study.steadyClock(context).bootCount }
    val summary = summaryDue(
        summaryState, summaryNow,
        com.ogenrwotdaniel.wakeproof.study.SteadyClock(android.os.SystemClock.elapsedRealtime(), bootCount)
    )

    val chatAllowed = viewModel.hasGeminiKey && (!timer.active || !timer.running ||
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
                activeConjecture = activeConjecture,
                quote = quote.takeIf { settings.focusQuotes },
                showTree = settings.deepFocus,
                onPark = viewModel::parkThought,
                onLogConjecture = { q -> viewModel.logConjecture(settings.activeSubject, q) },
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
            if (summary != null) {
                SummaryPrompt(
                    subject = summary.subject,
                    writeLeftSeconds = ((summaryWriteLeftMillis(summary, summaryNow, com.ogenrwotdaniel.wakeproof.study.SteadyClock(android.os.SystemClock.elapsedRealtime(), bootCount)) + 999) / 1_000).toInt(),
                    onPhotograph = onPhotographSummary
                )
                Spacer(Modifier.height(16.dp))
            }
            // Asked in the break (or after the last block), never over a running
            // focus block. Read on each tick, so it leaves when it goes stale.
            val recallState by viewModel.recall.collectAsStateWithLifecycle()
            val focusing = timer.active && timer.running && timer.phase == PomodoroPhase.FOCUS
            val recall = recallDue(recallState, System.currentTimeMillis()).takeIf { !focusing }
            if (recall != null) {
                RecallPrompt(
                    subject = recall.subject,
                    onSave = viewModel::saveRecall,
                    onSkip = viewModel::skipRecall
                )
                Spacer(Modifier.height(16.dp))
            }
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

            if (timer.active && timer.phase == PomodoroPhase.FOCUS && settings.deepFocus) {
                Spacer(Modifier.height(8.dp))
                TreeLine(timer = timer)
            }
            if (timer.active && settings.focusQuotes) {
                Spacer(Modifier.height(8.dp))
                QuoteLine(quote = quote)
            }

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
            activeConjecture?.let { conjecture ->
                Text(
                    text = "🔬 Active problem: $conjecture",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }
            val peer = rememberJoinablePeer()
            if (!timer.active && summary != null) {
                // No next session until this one's summary is in: starting one
                // would be the way to never write it.
                Text(
                    text = "The next session opens once the derivation page is photographed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else if (!timer.active && peer != null) {
                // A session already runs on the watch or another device. Starting
                // one here would join it anyway, so that is what is offered.
                PeerSessionCard(peer)
            } else if (!timer.active) {
                // Every session is timed and stops itself — there is no Free:
                // with no pause or stop, a session that never ends would never
                // let go of the phone.
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
                }
                Text(
                    text = "${settings.focusMinutes} min focus · ${settings.shortBreakMinutes} min " +
                        "break. 3 h is 3 × 25 min, a 15 min break, then 3 × 25 min. Once " +
                        "started, a session cannot be paused or stopped, and only your " +
                        "focus list (Settings → Study apps) opens until it ends — breaks " +
                        "included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else if (!timer.running) {
                // Only reachable for a session paused before pausing was removed.
                Button(onClick = { PomodoroTimerService.resume(context) }) { Text("Resume") }
            } else {
                Text(
                    text = "Locked in until the last pomodoro ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            ThoughtParking(
                thoughts = thoughts,
                activeSubject = settings.activeSubject,
                activeConjecture = activeConjecture,
                onPark = viewModel::parkThought,
                onLogConjecture = { q -> viewModel.logConjecture(settings.activeSubject, q) },
                onSelectConjecture = viewModel::selectActiveConjecture,
                onToggle = viewModel::setThoughtDone,
                onClearDone = viewModel::clearDoneThoughts
            )

            Spacer(Modifier.height(12.dp))
            MusicControls(
                focusEnabled = settings.focusMusicEnabled,
                breakEnabled = settings.breakMusicEnabled,
                soundId = settings.focusSoundId,
                onFocusChange = viewModel::setMusicEnabled,
                onBreakChange = viewModel::setBreakMusicEnabled,
                onSoundChange = viewModel::setFocusSound
            )

            if (settings.focusLockPhone) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (timer.active && timer.running) {
                        "🔒 Phone is pinned to Euler's Lab until the session ends"
                    } else {
                        "🔒 Phone locks to Euler's Lab for a whole session, breaks " +
                            "included (Settings)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            if (viewModel.hasGeminiKey) {
                StudyChatPanel(
                    messages = chat.messages,
                    sending = chat.sending,
                    socratic = chat.socratic,
                    error = chat.error,
                    enabled = chatAllowed,
                    onToggleSocratic = viewModel::setSocraticMode,
                    onSend = viewModel::sendChat,
                    // A fixed height, not weight(1f): inside a scrolling column
                    // the available height is infinite, so a weight is a crash
                    // and an unbounded LazyColumn would be one too.
                    modifier = Modifier.height(380.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        com.ogenrwotdaniel.wakeproof.study.StudyBrowserActivity.intent(context)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Blackboard & Research")
            }
            OutlinedButton(onClick = onOpenSummaries, modifier = Modifier.fillMaxWidth()) {
                Text("Session summaries & derivations")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

/**
 * After a session: Euler's 3-step closed-book derivation protocol written on a
 * page from memory for at least ten minutes ([writeLeftSeconds] of them still
 * to go), then photographed. There is no skip; the focus wall holds until the
 * photo is kept.
 */
@Composable
private fun SummaryPrompt(
    subject: String,
    writeLeftSeconds: Int,
    onPhotograph: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(modifier = modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Session done — Euler Derivation Protocol ($subject)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Ten minutes, closed-book, on paper from memory:\n" +
                    "1. Definitions & Theorems: state the core result or mechanism.\n" +
                    "2. Derivation / Counterexample: re-derive the crux step or construct a boundary example.\n" +
                    "3. Next Open Question: write one unresolved question at the bottom of the page.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (writeLeftSeconds > 0) {
                Text(
                    text = "%d:%02d".format(writeLeftSeconds / 60, writeLeftSeconds % 60),
                    style = MaterialTheme.typography.displaySmall
                )
            }
            Button(onClick = onPhotograph, enabled = writeLeftSeconds <= 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (writeLeftSeconds > 0) "Keep deriving on paper" else "Photograph the derivation page")
            }
            Text(
                text = "Your apps stay shut until the photo is kept, half an hour after the " +
                    "session at most.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * The screen during a focus block: the countdown, and as close to nothing else
 * as the app can honestly get away with.
 *
 * "Lock phone during focus" already stops you leaving Euler's Lab. That left the
 * app itself as the last thing to fiddle with mid-block — a study chat, a sound
 * picker, a subject list, five other screens — so a pinned phone became a phone
 * pinned to a menu. This is the other half of that lock.
 *
 * What survives, and why:
 *
 * * **Parking a thought or logging an Adversaria conjecture.** The one
 *   interaction that exists *because* you are mid-focus: it is how a
 *   distraction or sudden mathematical question gets written down in two taps
 *   instead of being chased.
 *
 * Everything else comes back by itself at the break.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusMode(
    timer: PomodoroState,
    subject: String,
    activeConjecture: String?,
    quote: FocusQuote?,
    showTree: Boolean,
    onPark: (String) -> Unit,
    onLogConjecture: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var parking by remember { mutableStateOf(false) }
    var isConjecture by remember { mutableStateOf(false) }
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
        activeConjecture?.let { conjecture ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "🔬 $conjecture",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp)
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
        if (showTree) {
            Spacer(Modifier.height(12.dp))
            TreeLine(timer = timer)
        }
        quote?.let {
            Spacer(Modifier.height(20.dp))
            QuoteLine(quote = it, modifier = Modifier.widthIn(max = 420.dp))
        }

        Spacer(Modifier.height(32.dp))
        if (parking) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (isConjecture) "Open problem or conjecture ($subject)…"
                                else "Park a distracting thought…"
                            )
                        },
                        singleLine = true
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = {
                            if (isConjecture) onLogConjecture(input) else onPark(input)
                            input = ""
                            parking = false
                        },
                        enabled = input.isNotBlank()
                    ) { Text(if (isConjecture) "Log" else "Park") }
                }
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { isConjecture = true; parking = true }) {
                    Text("🔬 Log open question")
                }
                TextButton(onClick = { isConjecture = false; parking = true }) {
                    Text("💭 Park a thought")
                }
                TextButton(
                    onClick = {
                        context.startActivity(
                            com.ogenrwotdaniel.wakeproof.study.StudyBrowserActivity.intent(context)
                        )
                    }
                ) {
                    Text("📚 Blackboard & Research")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "No pause, no stop. Only your focus list opens until the session ends.",
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
 * more than most sittings. 3 h replaced Free and is a fixed recipe, not a
 * target — see [sessionPlan].
 */
private val SESSION_LENGTHS = listOf(
    "1 h" to 60,
    "1 h 30" to 90,
    "2 h" to 120,
    "3 h" to THREE_HOUR_SESSION_MINUTES
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
                    placeholder = { Text("e.g. Graphene, Computer Vision, Mechanics") },
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
 * Euler's *Adversaria* (Open Problems & Conjectures) + deep-work "thought parking":
 * log an open mathematical/scientific question tagged with the active subject, pin
 * an open conjecture as the session's target, or dump a distracting thought in two
 * taps and return to work.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThoughtParking(
    thoughts: List<com.ogenrwotdaniel.wakeproof.data.db.ParkedThought>,
    activeSubject: String,
    activeConjecture: String?,
    onPark: (String) -> Unit,
    onLogConjecture: (String) -> Unit,
    onSelectConjecture: (String?) -> Unit,
    onToggle: (com.ogenrwotdaniel.wakeproof.data.db.ParkedThought, Boolean) -> Unit,
    onClearDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var isConjectureMode by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    val openConjectures = thoughts.count { !it.done && it.isAdversaria() }
    val openThoughts = thoughts.count { !it.done && !it.isAdversaria() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val counts = buildList {
                if (openConjectures > 0) add("$openConjectures open")
                if (openThoughts > 0) add("$openThoughts parked")
            }.joinToString(", ")
            Text(
                text = "🔬 Euler's Adversaria & Thoughts" + if (counts.isNotEmpty()) " ($counts)" else "",
                style = MaterialTheme.typography.bodyLarge
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Open")
            }
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isConjectureMode,
                    onClick = { isConjectureMode = true },
                    label = { Text("🔬 Open Conjecture ($activeSubject)") }
                )
                FilterChip(
                    selected = !isConjectureMode,
                    onClick = { isConjectureMode = false },
                    label = { Text("💭 Distracting Thought") }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (isConjectureMode) "e.g. Stone–Wales defect energy barrier vs. strain rate…"
                            else "Park a distraction for later…"
                        )
                    },
                    singleLine = true
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        if (isConjectureMode) onLogConjecture(input) else onPark(input)
                        input = ""
                    },
                    enabled = input.isNotBlank()
                ) { Text(if (isConjectureMode) "Log" else "Park") }
            }
            thoughts.take(8).forEach { thought ->
                val isConjecture = thought.isAdversaria()
                val isPinned = activeConjecture == thought.text
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
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (thought.done) TextDecoration.LineThrough else null,
                        color = when {
                            thought.done -> MaterialTheme.colorScheme.onSurfaceVariant
                            isConjecture -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (isConjecture && !thought.done) {
                        TextButton(
                            onClick = {
                                onSelectConjecture(if (isPinned) null else thought.text)
                            }
                        ) {
                            Text(if (isPinned) "Pinned" else "Focus")
                        }
                    }
                }
            }
            if (thoughts.any { it.done }) {
                TextButton(onClick = onClearDone) { Text("Clear resolved") }
            }
        }
    }
}

/**
 * Study-sound controls: independent focus/break toggles plus the bundled
 * ambient loop picker (shown while either toggle is on). Turning both toggles
 * off frees the audio output entirely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicControls(
    focusEnabled: Boolean,
    breakEnabled: Boolean,
    soundId: String,
    onFocusChange: (Boolean) -> Unit,
    onBreakChange: (Boolean) -> Unit,
    onSoundChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val picked = FocusSound.fromId(soundId)
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
                        selected = sound == picked,
                        onClick = { onSoundChange(sound.id) },
                        label = { Text(sound.label) }
                    )
                }
            }
            Text(
                text = picked.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The block's tree, Forest-style: it grows through the focus block and
 * withers if you leave for a distracting app. See `PomodoroTimerService.guardTree`.
 */
@Composable
private fun TreeLine(timer: PomodoroState, modifier: Modifier = Modifier) {
    val progress = if (timer.totalSeconds == 0) 0f
    else 1f - timer.remainingSeconds.toFloat() / timer.totalSeconds
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = FocusTree.growing(progress, timer.treeWithered),
            style = MaterialTheme.typography.displaySmall
        )
        Text(
            text = when {
                timer.treeWithered -> "This block's tree withered — it won't count. The next one can grow."
                timer.treeAtRisk -> "Come back — your tree is withering!"
                else -> "Your tree grows while you stay. Study apps are safe."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (timer.treeWithered || timer.treeAtRisk) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuoteLine(quote: FocusQuote, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "“${quote.text}”",
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center
        )
        Text(
            text = "— ${quote.author}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyChatPanel(
    messages: List<ChatMessage>,
    sending: Boolean,
    socratic: Boolean,
    error: String?,
    enabled: Boolean,
    onToggleSocratic: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (enabled) "Google Gemini study assistant (break time)"
                else "Google Gemini assistant — locked during focus",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !socratic,
                onClick = { onToggleSocratic(false) },
                enabled = enabled,
                label = { Text("Explain / Quiz") }
            )
            FilterChip(
                selected = socratic,
                onClick = { onToggleSocratic(true) },
                enabled = enabled,
                label = { Text("Euler Referee (Socratic)") }
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { message ->
                val speaker = when {
                    message.role == "user" -> "You: "
                    socratic -> "Referee: "
                    else -> "Coach: "
                }
                Text(
                    text = speaker + message.text,
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
                placeholder = {
                    Text(
                        if (socratic) "State your proof idea or conjecture…"
                        else "Quiz me / explain…"
                    )
                },
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

/**
 * Active recall at the end of a focus block: proof skeleton, key trick, or
 * boundary counterexample from memory. Saved lines wait on the Flashcards
 * screen to become cards.
 */
@Composable
private fun RecallPrompt(subject: String, onSave: (String) -> Unit, onSkip: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.Card(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Closed-Book Proof & Recall ($subject)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Without looking at your notes: write the proof skeleton, crux step, " +
                    "or a boundary counterexample from this block. It queues for your Flashcards deck.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(400) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = { onSave(text); text = "" },
                    enabled = text.isNotBlank()
                ) { Text("Save") }
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
    }
}

/**
 * The clock the "is a summary still owed" question is asked against, for the
 * timer screen and Home alike, so the two cannot disagree. Its own clock: once
 * a session has ended nothing else redraws the screen. Read afresh whenever
 * the ask changes: against a reading taken before it, a new ask would look
 * like one from the future and not be shown.
 */
@Composable
fun rememberSummaryNow(state: com.ogenrwotdaniel.wakeproof.study.SummaryState): Long {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), state) {
        while (true) {
            value = System.currentTimeMillis()
            // By the second only while something is owed: a lapsed ask left in
            // the file must not redraw the screen every second for ever.
            kotlinx.coroutines.delay(
                if (state.pending != null && kotlin.math.abs(value - state.pending.askedAtMillis) < SUMMARY_TICK_FOR_MILLIS) 1_000
                else 30_000
            )
        }
    }
    return now
}

/** How long after an ask the summary clock ticks by the second: the hold, and room for a clock set wrong. */
private const val SUMMARY_TICK_FOR_MILLIS = 3 * 60 * 60_000L
