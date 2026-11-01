package com.ogenrwotdaniel.wakeproof.agenda

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * "Focus for 1 h 25 min?" — the one confirm between a calendar event and a
 * session that cannot be stopped (2026-11-01). Shared by the home-screen
 * agenda and the 5-minute reminder, so both say the same thing about what is
 * being agreed to.
 */
@Composable
fun EventFocusDialog(
    title: String,
    minutes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Focus for ${eventFocusLabel(minutes)}?") },
        text = {
            Text(
                "\"$title\" — a focus session that ends with the event. Once it starts it " +
                    "can't be paused or stopped, and everything off your study list is walled."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}
