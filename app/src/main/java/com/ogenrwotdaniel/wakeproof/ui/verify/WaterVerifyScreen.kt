package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ogenrwotdaniel.wakeproof.ui.components.PlaceholderScreen

/**
 * Water verification. The two-photo (full/empty) Kimi vision flow arrives in
 * its own phase; this placeholder only proves navigation works.
 */
@Composable
fun WaterVerifyScreen(
    onVerified: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        title = "Water verification",
        description = "Photograph the full bottle, drink, then photograph it empty. Kimi judges.",
        modifier = modifier
    ) {
        Button(onClick = onVerified) {
            Text("Simulate verified (dev only)")
        }
    }
}
