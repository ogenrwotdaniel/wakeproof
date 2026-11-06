package com.ogenrwotdaniel.wakeproof.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ogenrwotdaniel.wakeproof.data.remote.GEMINI_KEY_HINT
import com.ogenrwotdaniel.wakeproof.data.remote.GEMINI_KEY_SOURCE
import com.ogenrwotdaniel.wakeproof.data.remote.GEMINI_KEY_URL

/**
 * Gemini API key management: paste a free Google AI Studio key (stored encrypted
 * on-device), test with a 1-token ping, or clear.
 */
@Composable
fun ApiKeySection(
    hasKey: Boolean,
    testing: Boolean,
    testResult: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var keyInput by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Gemini API key", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (hasKey) {
                "A Gemini key is saved (stored encrypted on-device)."
            } else {
                "Paste your key from $GEMINI_KEY_SOURCE. Flashcard drafting and the " +
                    "study chat stay disabled without it. Water verification does not " +
                    "need it — that runs on the device — but a key lets it ask for a " +
                    "second opinion before rejecting a photo."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasKey) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, GEMINI_KEY_URL.toUri()))
                    }
                }
            ) {
                Text("Get a free key")
            }
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(GEMINI_KEY_HINT) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(keyInput)
                    keyInput = ""
                },
                enabled = keyInput.isNotBlank()
            ) { Text("Save") }
            OutlinedButton(onClick = onTest, enabled = hasKey && !testing) {
                Text(if (testing) "Testing…" else "Test connection")
            }
            OutlinedButton(onClick = onClear, enabled = hasKey) { Text("Clear") }
        }
        testResult?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}
