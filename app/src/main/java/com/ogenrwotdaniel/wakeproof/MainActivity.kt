package com.ogenrwotdaniel.wakeproof

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.ogenrwotdaniel.wakeproof.ui.navigation.WakeProofNavGraph
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's single main activity. The lock-screen alarm flow lives in
 * [com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingActivity].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Interim ask until the onboarding phase owns permissions: without
        // POST_NOTIFICATIONS the alarm's full-screen notification cannot appear.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            WakeProofTheme {
                WakeProofNavGraph()
            }
        }
    }
}
