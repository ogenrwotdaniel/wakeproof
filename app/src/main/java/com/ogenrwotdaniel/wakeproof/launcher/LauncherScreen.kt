package com.ogenrwotdaniel.wakeproof.launcher

import androidx.activity.compose.BackHandler
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import com.ogenrwotdaniel.wakeproof.ui.formatDurationMinutes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * Seconds of "are you sure?" before an app you have capped opens. Was 5, which
 * is short enough to wait out on autopilot; 45 is long enough to be dull, and
 * dull is the point — the urge to open an app rarely survives a minute of
 * nothing happening.
 */
private const val MINDFUL_PAUSE_SECONDS = 45

/** How long each line of [MINDFUL_PAUSE_LINES] stays up. */
private const val MINDFUL_LINE_SECONDS = 9

/**
 * Shown one after another while the pause runs. Deliberately plain: no
 * animation, nothing to look at, and every line an honest reason to tap
 * "Not now" instead of waiting.
 */
private val MINDFUL_PAUSE_LINES = listOf(
    "Nothing in there is new since last time.",
    "In ten minutes you won't remember what you saw.",
    "This wait is the most boring part of your day. The app is the second.",
    "You could have read a page of your notes by now.",
    "Still here? The urge usually passes before the timer does."
)

/** How often "Other apps" reshuffles. Long enough to read, too short to learn. */
private const val SHUFFLE_SECONDS = 4

/**
 * The home screen proper. Two views: HOME (clock, today, agenda, the permanent
 * tools and a few pins) and OTHER APPS. Back from OTHER APPS returns home; back
 * on HOME does nothing, as on any launcher.
 *
 * Built to be the least interesting screen on the phone. There is no search
 * and no alphabetical list on purpose: both exist to get to an app as fast as
 * possible, which is the habit this screen is trying to break. "Other apps" is
 * a plain grey list that reshuffles every [SHUFFLE_SECONDS] seconds, so no app
 * has a place to be found by muscle memory — you read the list or you give up.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    onLaunch: (String) -> Unit,
    onWait: (InstalledApp) -> Unit,
    onOpenWakeProof: () -> Unit,
    onAppInfo: (String) -> Unit
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val gated by viewModel.gated.collectAsStateWithLifecycle()
    val agenda by viewModel.agenda.collectAsStateWithLifecycle()
    val doneKeys by viewModel.doneEventKeys.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val limits by viewModel.limits.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showOthers by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf<InstalledApp?>(null) }
    BackHandler(enabled = showOthers) { showOthers = false }

    var calendarAllowed by remember { mutableStateOf(viewModel.hasCalendarPermission()) }
    val askCalendar = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarAllowed = granted
        if (granted) viewModel.refresh()
    }

    // Social, video and messaging apps sit out the five-minute wait — the same
    // one AppBlockService throws up however else they are opened. Other capped
    // apps get the shorter mindful pause; everything else opens at once.
    val open: (InstalledApp) -> Unit = { app ->
        when {
            app.packageName in gated -> onWait(app)
            app.packageName in limits -> pausing = app
            else -> onLaunch(app.packageName)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        if (showOthers) {
            val pinnedPackages = pinned.map { it.packageName }.toSet() + settings.launcherFavorites
            OtherApps(
                apps = apps.filter { it.packageName !in pinnedPackages },
                canPin = viewModel::canPin,
                onOpen = { open(it); showOthers = false },
                onPin = viewModel::toggleFavorite,
                onAppInfo = onAppInfo,
                onClose = { showOthers = false }
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Clock()
                Spacer(Modifier.height(4.dp))
                viewModel.nextAlarmMillis()?.let { millis ->
                    val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                    Text(
                        text = "⏰ ${at.format(DateTimeFormatter.ofPattern("EEE"))} " +
                            formatAlarmTime(context, at.hour, at.minute),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "${formatDurationMinutes(today.focusMinutes)} of " +
                        "${formatDurationMinutes(settings.dailyFocusGoalMinutes)} focused" +
                        if (streak.days > 0) "  ·  🔥 ${streak.days}" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.LightGray,
                    modifier = Modifier.combinedClickable(onClick = onOpenWakeProof)
                )
                Text(
                    text = if (timer.active) "${timer.phase.label} running — open"
                    else "Start focusing ›",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    // A session cannot be stopped once started, so starting one
                    // is a choice made on the timer screen, never a stray tap here.
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .combinedClickable(onClick = onOpenWakeProof)
                )

                Spacer(Modifier.height(20.dp))
                Agenda(
                    events = agenda,
                    doneKeys = doneKeys,
                    allowed = calendarAllowed,
                    onAllow = { askCalendar.launch(Manifest.permission.READ_CALENDAR) },
                    onToggleDone = viewModel::setEventDone,
                    // Takes all the space between the focus line and the pins,
                    // so the whole day is visible at once; scrolls only on a day
                    // with more than fits.
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                val byPackage = apps.associateBy { it.packageName }
                val favorites = settings.launcherFavorites
                    .filter { it !in gated && it !in limits }
                    .mapNotNull { byPackage[it] }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    pinned.forEach { app ->
                        AppRow(
                            app = app,
                            big = true,
                            pinMenu = null,
                            onOpen = { open(app) },
                            onAppInfo = { onAppInfo(app.packageName) }
                        )
                    }
                    favorites.forEach { app ->
                        AppRow(
                            app = app,
                            big = true,
                            pinMenu = "Remove from home",
                            onOpen = { open(app) },
                            onPinMenu = { viewModel.toggleFavorite(app.packageName) },
                            onAppInfo = { onAppInfo(app.packageName) }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onOpenWakeProof) { Text("WakeProof", color = Color.Gray) }
                    TextButton(onClick = { showOthers = true }) { Text("Other apps", color = Color.Gray) }
                }
            }
        }

        pausing?.let { app ->
            MindfulPause(
                app = app,
                limit = limits[app.packageName],
                usedMinutes = { viewModel.usedMinutesToday(app.packageName) },
                onOpen = {
                    pausing = null
                    onLaunch(app.packageName)
                },
                onCancel = { pausing = null }
            )
        }
    }
}

/**
 * All of today's events from the phone's calendar, as plain lines: all-day
 * ones first, then by start time, titles wrapping rather than cut off. Once
 * capped at six rows with "+ n more"; the user wanted the whole day in view,
 * and the home screen had the room. A tap ticks an event off (the same
 * done-state as the Agenda screen in the app). Without the calendar permission
 * it is one line offering to ask.
 */
@Composable
private fun Agenda(
    events: List<AgendaEvent>,
    doneKeys: Set<String>,
    allowed: Boolean,
    onAllow: () -> Unit,
    onToggleDone: (AgendaEvent, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (!allowed) {
        Text(
            text = "Show my calendar here ›",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = modifier.clickable(onClick = onAllow)
        )
        return
    }
    if (events.isEmpty()) {
        Text(
            text = "Nothing on the calendar today.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.DarkGray,
            modifier = modifier
        )
        return
    }
    val ordered = remember(events) { events.sortedWith(compareBy({ !it.allDay }, { it.beginMillis })) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = "Today",
            style = MaterialTheme.typography.labelLarge,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        ordered.forEach { event ->
            val done = event.key in doneKeys
            val start = Instant.ofEpochMilli(event.beginMillis).atZone(ZoneId.systemDefault())
            Text(
                text = (if (event.allDay) "All day" else formatAlarmTime(context, start.hour, start.minute)) +
                    "   " + event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) Color.DarkGray else Color.LightGray,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleDone(event, !done) }
                    .padding(vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // Wake on the next minute boundary, not every second.
            delay((60 - now.second) * 1_000L)
        }
    }
    val context = LocalContext.current
    Text(
        text = formatAlarmTime(context, now.hour, now.minute),
        fontSize = 64.sp,
        fontWeight = FontWeight.Light,
        color = Color.White
    )
    Text(
        text = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
        style = MaterialTheme.typography.titleMedium,
        color = Color.Gray
    )
}

/**
 * One app as a line of text. Long-press offers App info, plus the pin action
 * named by [pinMenu] when there is one (null for the permanent tools and for
 * apps that may not be pinned).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: InstalledApp,
    big: Boolean,
    pinMenu: String?,
    onOpen: () -> Unit,
    onAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
    onPinMenu: () -> Unit = {}
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            text = app.label,
            fontSize = if (big) 22.sp else 17.sp,
            color = if (big) Color.White else Color.Gray,
            modifier = Modifier
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(vertical = if (big) 6.dp else 9.dp)
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (pinMenu != null) {
                DropdownMenuItem(
                    text = { Text(pinMenu) },
                    onClick = {
                        menu = false
                        onPinMenu()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("App info") },
                onClick = {
                    menu = false
                    onAppInfo()
                }
            )
        }
    }
}

/**
 * Every other app, in an order that changes every [SHUFFLE_SECONDS] seconds.
 * No search, no alphabet, no icons, no usage badges: nothing that helps an
 * app be found fast, because finding one fast is the reflex being starved.
 */
@Composable
private fun OtherApps(
    apps: List<InstalledApp>,
    canPin: (String) -> Boolean,
    onOpen: (InstalledApp) -> Unit,
    onPin: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onClose: () -> Unit
) {
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SHUFFLE_SECONDS * 1_000L)
            round++
        }
    }
    val shown = remember(apps, round) { apps.shuffled() }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Other apps", color = Color.DarkGray, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClose) { Text("Close", color = Color.Gray) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    big = false,
                    pinMenu = if (canPin(app.packageName)) "Pin to home" else null,
                    onOpen = { onOpen(app) },
                    onPinMenu = { onPin(app.packageName) },
                    onAppInfo = { onAppInfo(app.packageName) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * [MINDFUL_PAUSE_SECONDS] between tapping a capped app and it opening, with
 * today's usage on screen and a slow run of lines arguing for "Not now". Not a
 * wall — the block itself is AppBlockService's job — just a gap long and dull
 * enough that "do I actually want this?" wins, which is where most reflexive
 * opens die. Leaving the screen starts the wait over.
 */
@Composable
private fun MindfulPause(
    app: InstalledApp,
    limit: AppLimit?,
    usedMinutes: suspend () -> Long,
    onOpen: () -> Unit,
    onCancel: () -> Unit
) {
    var secondsLeft by remember(app) { mutableIntStateOf(MINDFUL_PAUSE_SECONDS) }
    var used by remember(app) { mutableLongStateOf(-1L) }
    LaunchedEffect(app) {
        used = usedMinutes()
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }
    // Waiting it out in another app and coming back must not count: the point
    // is to sit with nothing to do. Leaving the home screen cancels the pause.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onCancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val elapsed = MINDFUL_PAUSE_SECONDS - secondsLeft
    val line = MINDFUL_PAUSE_LINES[(elapsed / MINDFUL_LINE_SECONDS).coerceAtMost(MINDFUL_PAUSE_LINES.lastIndex)]
    BackHandler(onBack = onCancel)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Text(text = "Breathe.", fontSize = 40.sp, color = Color.White, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Do you really want to open ${app.label}?",
                style = MaterialTheme.typography.titleMedium,
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
            if (used >= 0 && limit != null) {
                Text(
                    text = "$used of ${limit.minutes} min used today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (used >= limit.minutes) Color(0xFFFF8A80) else Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(32.dp))
            TextButton(onClick = onCancel) {
                Text("Not now", color = Color.White, fontSize = 20.sp)
            }
            TextButton(onClick = onOpen, enabled = secondsLeft == 0) {
                Text(
                    text = if (secondsLeft > 0) "Open in $secondsLeft…" else "Open ${app.label}",
                    color = Color.Gray
                )
            }
        }
    }
}
