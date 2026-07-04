package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ogenrwotdaniel.wakeproof.ui.components.PlaceholderScreen

/**
 * Pushup verification. The camera + ML Kit pose detection implementation
 * arrives in its own phase; this placeholder only proves navigation works.
 */
@Composable
fun PushupVerifyScreen(
    onVerified: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        title = "Pushup verification",
        description = "CameraX + ML Kit pose detection will count 15 pushups here.",
        modifier = modifier
    ) {
        Button(onClick = onVerified) {
            Text("Simulate verified (dev only)")
        }
    }
}
