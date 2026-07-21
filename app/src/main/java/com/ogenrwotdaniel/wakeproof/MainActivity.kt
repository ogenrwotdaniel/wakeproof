package com.ogenrwotdaniel.wakeproof

import android.app.ActivityManager
import android.os.Bundle
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
import kotlinx.coroutines.launch
import com.ogenrwotdaniel.wakeproof.ui.MainViewModel
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

    override fun onStart() {
        super.onStart()
        AppForeground.set(true)
    }

    override fun onStop() {
        AppForeground.set(false)
        // Refresh the home-screen widget and the paired watch tile with whatever
        // changed this session.
        lifecycleScope.launch {
            runCatching { WakeProofWidget().updateAll(applicationContext) }
            runCatching { wearStatsSync.push() }
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // "Can't use the phone during focus": pin the app (lock task / screen
        // pinning) while a focus countdown runs, release it on break/pause/stop.
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
        setContent {
            WakeProofTheme {
                val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
                when (onboardingComplete) {
                    null -> Unit // settings still loading; theme background shows
                    true -> WakeProofNavGraph(startDestination = HomeRoute)
                    false -> WakeProofNavGraph(startDestination = OnboardingRoute)
                }
            }
        }
    }
}
