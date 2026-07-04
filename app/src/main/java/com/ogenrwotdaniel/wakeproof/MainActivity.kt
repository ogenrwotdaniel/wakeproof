package com.ogenrwotdaniel.wakeproof

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ogenrwotdaniel.wakeproof.ui.navigation.WakeProofNavGraph
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's single activity. All screens live in the Compose navigation graph;
 * the separate lock-screen alarm activity arrives with the alarm engine phase.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WakeProofTheme {
                WakeProofNavGraph()
            }
        }
    }
}
