package com.ogenrwotdaniel.wakeproof.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Press-and-hold button that fires [onCompleted] only after an uninterrupted
 * [holdSeconds]-second hold. Used for the emergency dismiss and the
 * honor-mode water fallback — long enough to be a real commitment.
 */
@Composable
fun HoldToDismissButton(
    label: String,
    holdSeconds: Int,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pressed) {
        if (!pressed) {
            progress = 0f
            return@LaunchedEffect
        }
        val stepMs = 50L
        val totalMs = holdSeconds * 1000L
        var elapsed = 0L
        while (elapsed < totalMs) {
            delay(stepMs)
            elapsed += stepMs
            progress = elapsed.toFloat() / totalMs
        }
        onCompleted()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )
            Text(
                text = if (pressed) "Keep holding… ${(progress * holdSeconds).toInt()}/$holdSeconds s"
                else label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
