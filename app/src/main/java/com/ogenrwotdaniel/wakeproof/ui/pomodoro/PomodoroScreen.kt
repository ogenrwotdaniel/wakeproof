package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ogenrwotdaniel.wakeproof.ui.components.PlaceholderScreen

/**
 * Pomodoro study mode. The 25/5 timer with its foreground service arrives in
 * its own phase; the legacy/pomodorotimerapp sources are reference material.
 */
@Composable
fun PomodoroScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        title = "Pomodoro",
        description = "25 min focus / 5 min break cycles with streak tracking.",
        modifier = modifier
    ) {
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
    }
}
