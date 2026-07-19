package com.ogenrwotdaniel.wakeproof.ui.flashcards

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.FlashcardDraft
import com.ogenrwotdaniel.wakeproof.data.ReviewGrade
import com.ogenrwotdaniel.wakeproof.data.db.SubjectCardCount

/**
 * Spaced-repetition flashcards: decks by subject with due counts, an SM-2
 * review flow (tap to reveal, grade Again/Hard/Good/Easy), and card creation —
 * manual, AI-drafted from pasted notes, or from a photo of notes.
 */
@Composable
fun FlashcardsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlashcardsViewModel = hiltViewModel()
) {
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
        ) {
            val session = review
            if (session != null) {
                ReviewPane(
                    session = session,
                    onReveal = viewModel::reveal,
                    onGrade = viewModel::grade,
                    onEnd = viewModel::endReview,
                    modifier = Modifier.weight(1f)
                )
            } else {
                DeckPane(
                    subjects = subjects,
                    drafts = drafts,
                    hasApiKey = viewModel.hasApiKey,
                    viewModel = viewModel,
                    onBack = onBack,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ReviewPane(
    session: FlashcardsViewModel.ReviewState,
    onReveal: () -> Unit,
    onGrade: (ReviewGrade) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val card = session.queue.first()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = session.subject, style = MaterialTheme.typography.headlineMedium)
        LinearProgressIndicator(
            progress = {
                if (session.totalAtStart == 0) 0f
                else session.gradedCount.toFloat() / (session.gradedCount + session.queue.size)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${session.queue.size} left",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(enabled = !session.revealed) { onReveal() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = card.front,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                AnimatedContent(targetState = session.revealed, label = "answer") { revealed ->
                    if (revealed) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            Text(
                                text = card.back,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Text(
                            text = "Tap to reveal",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (session.revealed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReviewGrade.entries.forEach { grade ->
                    OutlinedButton(
                        onClick = { onGrade(grade) },
                        modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp)
                    ) { Text(grade.label) }
                }
            }
        }
        TextButton(onClick = onEnd) { Text("End review") }
    }
}

@Composable
private fun DeckPane(
    subjects: List<SubjectCardCount>,
    drafts: FlashcardsViewModel.DraftState,
    hasApiKey: Boolean,
    viewModel: FlashcardsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subjectInput by rememberSaveable { mutableStateOf("") }
    var notesInput by rememberSaveable { mutableStateOf("") }
    var frontInput by rememberSaveable { mutableStateOf("") }
    var backInput by rememberSaveable { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::generateFromPhoto) }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(text = "Flashcards", style = MaterialTheme.typography.headlineMedium)
        }

        items(subjects, key = { it.subject }) { deck ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = deck.subject, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${deck.total} cards · ${deck.due} due",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (deck.due > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.deleteSubject(deck.subject) }) {
                        Text("Delete")
                    }
                    Button(
                        onClick = { viewModel.startReview(deck.subject) },
                        enabled = deck.due > 0
                    ) { Text("Review") }
                }
            }
        }

        if (subjects.isEmpty()) {
            item {
                Text(
                    text = "No decks yet — add cards below. Snap a photo of your notes and " +
                        "let the AI draft cards for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { HorizontalDivider() }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Add cards", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = subjectInput,
                    onValueChange = { subjectInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Subject (e.g. Calculus)") },
                    singleLine = true
                )

                if (drafts.drafts.isEmpty()) {
                    OutlinedTextField(
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Paste notes for the AI (optional)") },
                        minLines = 3
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.generateFromText(notesInput) },
                            enabled = hasApiKey && notesInput.isNotBlank() && !drafts.generating
                        ) { Text("Draft with AI") }
                        OutlinedButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            enabled = hasApiKey && !drafts.generating
                        ) { Text("From notes photo") }
                        if (drafts.generating) CircularProgressIndicator()
                    }
                    if (!hasApiKey) {
                        Text(
                            text = "AI drafting needs an API key (Settings → AI provider — " +
                                "Gemini is free).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    drafts.error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (drafts.drafts.isNotEmpty()) {
            item {
                Text(
                    text = "${drafts.drafts.size} drafted cards — remove any you don't want, " +
                        "then save.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(drafts.drafts) { draft ->
                DraftRow(draft = draft, onRemove = { viewModel.removeDraft(draft) })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.saveDrafts(subjectInput) },
                        enabled = subjectInput.isNotBlank()
                    ) { Text("Save all") }
                    OutlinedButton(onClick = viewModel::discardDrafts) { Text("Discard") }
                }
                if (subjectInput.isBlank()) {
                    Text(
                        text = "Enter a subject above to save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = frontInput,
                    onValueChange = { frontInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Front (question)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = backInput,
                    onValueChange = { backInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Back (answer)") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        viewModel.addManualCard(subjectInput, frontInput, backInput)
                        frontInput = ""
                        backInput = ""
                    },
                    enabled = subjectInput.isNotBlank() && frontInput.isNotBlank() &&
                        backInput.isNotBlank()
                ) { Text("Add one card") }
            }
        }

        item { TextButton(onClick = onBack) { Text("Back") } }
    }
}

@Composable
private fun DraftRow(
    draft: FlashcardDraft,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = draft.front, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = draft.back,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}
