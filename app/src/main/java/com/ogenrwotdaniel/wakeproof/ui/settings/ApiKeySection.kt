package com.ogenrwotdaniel.wakeproof.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.ogenrwotdaniel.wakeproof.data.remote.AiProvider
import android.content.Intent

/**
 * AI provider + API key management: pick Kimi (paid) or Gemini (free tier),
 * paste the matching key (stored encrypted, per provider), test with a
 * 1-token ping, clear.
 */
@Composable
fun ApiKeySection(
    provider: AiProvider,
    hasKey: Boolean,
    testing: Boolean,
    testResult: String?,
    onProviderChange: (AiProvider) -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var keyInput by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "AI provider", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == provider,
                    onClick = { onProviderChange(candidate) },
                    label = { Text(candidate.label) }
                )
            }
        }
        Text(
            text = if (hasKey) {
                "A ${provider.label} key is saved (stored encrypted on-device)."
            } else {
                "Paste your key from ${provider.keySource}. Water verification and the " +
                    "AI coach stay disabled without it."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasKey) {
            // The key is the one thing the user cannot get from inside the app, and
            // "go to aistudio.google.com/api-keys" typed on a phone is where people
            // give up. One tap out to the page instead.
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, provider.keyUrl.toUri()))
                    }
                }
            ) {
                Text(if (provider == AiProvider.GEMINI) "Get a free key" else "Get a key")
            }
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(provider.keyHint) },
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
