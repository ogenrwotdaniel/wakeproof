package com.ogenrwotdaniel.wakeproof

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.ui.MainViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
