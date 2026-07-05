package com.ogenrwotdaniel.wakeproof.ui.settings

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Kimi API key management: paste, save into EncryptedSharedPreferences, test
 * with a 1-token ping, clear.
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

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Kimi API key", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (hasKey) "A key is saved (stored encrypted on-device)."
            else "Paste your key from platform.moonshot.ai. Water verification and the " +
                "AI coach stay disabled without it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("sk-…") },
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
