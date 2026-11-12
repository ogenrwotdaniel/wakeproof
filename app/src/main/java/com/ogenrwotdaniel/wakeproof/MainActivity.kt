package com.ogenrwotdaniel.wakeproof

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ogenrwotdaniel.wakeproof.data.sync.SyncEngine
import com.ogenrwotdaniel.wakeproof.data.sync.SyncResult
import com.ogenrwotdaniel.wakeproof.data.sync.SyncWorker
import com.ogenrwotdaniel.wakeproof.distraction.AppBlockService
import com.ogenrwotdaniel.wakeproof.ui.MainViewModel
import com.ogenrwotdaniel.wakeproof.wear.WatchBlockImporter
import com.ogenrwotdaniel.wakeproof.wear.WearStatsSync
import com.ogenrwotdaniel.wakeproof.widget.WakeProofWidget
import javax.inject.Inject
import com.ogenrwotdaniel.wakeproof.ui.navigation.HomeRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.OnboardingRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.WakeProofNavGraph
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's single main activity. First launch lands on onboarding (permission
 * walkthrough); the lock-screen alarm flow lives in
 * [com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingActivity].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var wearStatsSync: WearStatsSync

    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var watchBlockImporter: WatchBlockImporter

    /** Counts the asks to open the focus timer (the session-summary notification's tap). */
    private val timerAsks = MutableStateFlow(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) timerAsks.update { it + 1 }
    }

    override fun onStart() {
        super.onStart()
        AppForeground.set(true)
        // Bring the app-limit guard up (or down) to match the current limits and
        // grants. Done from a visible Activity, which is always allowed to start a
        // foreground service.
        lifecycleScope.launch { runCatching { AppBlockService.sync(applicationContext) } }
        // The removed day plan's photos and the evening reviews are not kept.
        lifecycleScope.launch { runCatching { com.ogenrwotdaniel.wakeproof.plan.deleteRetiredFiles(applicationContext) } }
        // A session lost to a force-stop comes back the moment the app opens.
        com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService.restore(applicationContext)
        com.ogenrwotdaniel.wakeproof.alarm.StrictAlarm.resumeIfPending(applicationContext)
        // The next calendar-event reminder, from a calendar that may have changed.
        lifecycleScope.launch {
            runCatching { com.ogenrwotdaniel.wakeproof.agenda.EventReminders.reschedule(applicationContext) }
            // ...and the same calendar onto the watch's "Today" list.
            com.ogenrwotdaniel.wakeproof.wear.WearAgendaSync.push(applicationContext)
        }
        // Pull whatever the other device did before the user reads anything. This is
        // the case that matters: picking up the tablet after studying on the phone.
        // sync() is a no-op unless signed in and enabled, and never throws.
        lifecycleScope.launch {
            // Watch blocks whose Data Layer event was missed — see WatchBlockImporter.
            runCatching { watchBlockImporter.sweep() }
            if (syncEngine.sync() is SyncResult.Success) {
                runCatching { WakeProofWidget().updateAll(applicationContext) }
            }
        }
    }

    override fun onStop() {
        AppForeground.set(false)
        // Refresh the home-screen widget and the paired watch tile with whatever
        // changed this session.
        lifecycleScope.launch {
            runCatching { WakeProofWidget().updateAll(applicationContext) }
            runCatching { wearStatsSync.push() }
        }
        // Upload this session's changes so the other device sees them now rather
        // than at the next hourly pass. Handed to WorkManager because this scope is
        // about to be torn down.
        SyncWorker.requestOneOff(applicationContext)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not after a rotation: the restored back stack already holds the timer.
        // Nor from Recents, which hands the notification's old intent over again.
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents && intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) {
            timerAsks.value = 1
        }
        // "Can't use the phone during a session": pin the app (lock task / screen
        // pinning) while a countdown runs — breaks included — and release it only
        // on pause/stop.
        // Only while RESUMED — startLockTask() is a no-op for background tasks.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.focusLockActive.collect { lock ->
                    val pinned = getSystemService(ActivityManager::class.java)
                        .lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
                    runCatching {
                        if (lock && !pinned) startLockTask()
                        else if (!lock && pinned) stopLockTask()
                    }
                }
            }
        }
        // "Screen must not go off during focus": held on the window, so it covers
        // every screen in the app while a focus countdown runs (the Pomodoro screen
        // does not have to be the one on top). STARTED, not RESUMED, so it survives
        // a transparent dialog/permission sheet on top.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.keepScreenOnActive.collect { keepOn ->
                    if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        setContent {
            WakeProofTheme {
                val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
                val immersiveFocus by viewModel.immersiveFocusActive.collectAsStateWithLifecycle()
                val openTimer by timerAsks.collectAsStateWithLifecycle()
                when (onboardingComplete) {
                    null -> Unit // settings still loading; theme background shows
                    true -> WakeProofNavGraph(
                        startDestination = HomeRoute,
                        immersiveFocus = immersiveFocus,
                        openTimer = openTimer
                    )
                    false -> WakeProofNavGraph(startDestination = OnboardingRoute)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_OPEN_TIMER = "open_timer"

        /**
         * Opens the app on the focus timer, with Home under it. The running
         * activity is reused: a second one on top would leave two back stacks.
         */
        fun timerIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TIMER, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
