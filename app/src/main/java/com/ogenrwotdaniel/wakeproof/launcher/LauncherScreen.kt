package com.ogenrwotdaniel.wakeproof.launcher

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import com.ogenrwotdaniel.wakeproof.ui.formatDurationMinutes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Seconds of "are you sure?" before an app you have capped opens. */
private const val MINDFUL_PAUSE_SECONDS = 5

/**
 * The home screen proper. Two views: HOME (clock, today, favourites) and ALL
 * APPS (search + alphabetical list). Back from ALL APPS returns home; back on
 * HOME does nothing, as on any launcher.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    onLaunch: (String) -> Unit,
    onOpenWakeProof: () -> Unit,
    onAppInfo: (String) -> Unit
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val limits by viewModel.limits.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAll by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf<InstalledApp?>(null) }
    BackHandler(enabled = showAll) { showAll = false }

    // Capped apps go through the mindful pause; everything else opens at once.
    val open: (InstalledApp) -> Unit = { app ->
        if (app.packageName in limits) pausing = app
        else onLaunch(app.packageName)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        if (showAll) {
            AllApps(
                apps = apps,
                favorites = settings.launcherFavorites,
                limits = limits,
                onOpen = { open(it); showAll = false },
                onToggleFavorite = viewModel::toggleFavorite,
                onAppInfo = onAppInfo,
                onClose = { showAll = false }
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
                    text = when {
                        timer.active -> "${timer.phase.label} running — open"
                        else -> "Start focusing ›"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .combinedClickable(
                            onClick = {
                                if (timer.active) onOpenWakeProof()
                                else PomodoroTimerService.start(context)
                            }
                        )
                )

                Spacer(Modifier.weight(1f))

                val byPackage = apps.associateBy { it.packageName }
                val favorites = settings.launcherFavorites.mapNotNull { byPackage[it] }
                if (favorites.isEmpty()) {
                    Text(
                        text = "Long-press any app in the list to pin it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                favorites.forEach { app ->
                    AppRow(
                        app = app,
                        limit = limits[app.packageName],
                        big = true,
                        isFavorite = true,
                        onOpen = { open(app) },
                        onToggleFavorite = { viewModel.toggleFavorite(app.packageName) },
                        onAppInfo = { onAppInfo(app.packageName) }
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onOpenWakeProof) { Text("WakeProof", color = Color.Gray) }
                    TextButton(onClick = { showAll = true }) { Text("All apps ›", color = Color.White) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: InstalledApp,
    limit: AppLimit?,
    big: Boolean,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAppInfo: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            text = app.label + when (limit?.mode) {
                LimitMode.BLOCK -> "  🔒"
                LimitMode.NUDGE -> "  ⏳"
                null -> ""
            },
            fontSize = if (big) 28.sp else 20.sp,
            // Capped apps are drawn dim: still there, just not inviting.
            color = if (limit != null) Color.Gray else Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(vertical = if (big) 8.dp else 10.dp)
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Remove from home" else "Pin to home") },
                onClick = {
                    menu = false
                    onToggleFavorite()
                }
            )
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

@Composable
private fun AllApps(
    apps: List<InstalledApp>,
    favorites: List<String>,
    limits: Map<String, AppLimit>,
    onOpen: (InstalledApp) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { Text("Close", color = Color.Gray) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    limit = limits[app.packageName],
                    big = false,
                    isFavorite = app.packageName in favorites,
                    onOpen = { onOpen(app) },
                    onToggleFavorite = { onToggleFavorite(app.packageName) },
                    onAppInfo = { onAppInfo(app.packageName) }
                )
            }
        }
    }
}

/**
 * Five seconds between tapping a capped app and it opening, with today's usage
 * on screen. Not a wall — the block itself is AppBlockService's job — just a
 * gap long enough for "do I actually want this?" to get a word in, which is
 * where most of the reflexive opens die.
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
