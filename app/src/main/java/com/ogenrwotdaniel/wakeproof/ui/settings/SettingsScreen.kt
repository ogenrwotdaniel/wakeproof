package com.ogenrwotdaniel.wakeproof.ui.settings

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ogenrwotdaniel.wakeproof.ui.components.PlaceholderScreen

/**
 * Settings. Alarm times, pushup target, water amount, Kimi API key management,
 * and the reliability check all land here in later phases.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        title = "Settings",
        description = "Alarm times, targets, and Kimi API key will be configured here.",
        modifier = modifier
    ) {
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
    }
}
