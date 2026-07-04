package com.ogenrwotdaniel.wakeproof.ui.onboarding

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ogenrwotdaniel.wakeproof.ui.components.PlaceholderScreen

/**
 * First-launch onboarding. The permission walkthrough (notifications, camera,
 * full-screen intent, exact alarm, Samsung battery settings) lands here in the
 * hardening phase.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        title = "Welcome to WakeProof",
        description = "Permission and battery setup walkthrough will live here.",
        modifier = modifier
    ) {
        Button(onClick = onDone) {
            Text("Done")
        }
    }
}
