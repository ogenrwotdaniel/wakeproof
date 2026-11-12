package com.ogenrwotdaniel.wakeproof.study

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Two lines on what a focus block taught, waiting to be made into flashcards. */
@Serializable
data class RecallNote(
    val id: String,
    val text: String,
    val subject: String,
    val epochDay: Long,
    val atMillis: Long
)

/** A finished focus block whose recall has not been written yet. */
@Serializable
data class PendingRecall(val subject: String, val epochDay: Long, val askedAtMillis: Long)

@Serializable
data class RecallState(
    val pending: PendingRecall? = null,
    val notes: List<RecallNote> = emptyList(),
    /**
     * Ids of notes discarded or made into cards here, newest last, so an older
     * backup that still holds one cannot bring it back.
     */
    val removed: List<String> = emptyList()
)

/** Removed ids remembered; far more than the notes a backup can hold. */
const val MAX_REMOVED_RECALLS = 1000

/** Pure: [state] without the note [id], which a later restore must not return. */
fun withoutNote(state: RecallState, id: String): RecallState = state.copy(
    notes = state.notes.filterNot { it.id == id },
    removed = (state.removed - id + id).takeLast(MAX_REMOVED_RECALLS)
)

/** A recall asked longer ago than this is no longer asked: the block is not fresh. */
const val RECALL_ASK_FOR_MILLIS = 30 * 60_000L

/** Notes kept at most; the oldest go first. */
const val MAX_RECALL_NOTES = 200

/** Pure: the recall still worth asking for at [now], or null. */
fun recallDue(state: RecallState, now: Long): PendingRecall? =
    state.pending?.takeIf { now - it.askedAtMillis in 0..RECALL_ASK_FOR_MILLIS }

/** Pure: [state] with [text] saved against its pending block. Blank text only clears the ask. */
fun withRecall(state: RecallState, text: String, id: String, now: Long): RecallState {
    val pending = state.pending ?: return state
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return state.copy(pending = null)
    val note = RecallNote(id, trimmed, pending.subject, pending.epochDay, now)
    return state.copy(pending = null, notes = (state.notes + note).takeLast(MAX_RECALL_NOTES))
}

/**
 * Pure: [state] with a backup's [restored] notes merged in by id, oldest first.
 * A note in both takes the backup's; one removed on this device stays removed;
 * a pending ask is not part of a backup.
 */
fun withRestoredNotes(state: RecallState, restored: List<RecallNote>): RecallState {
    val gone = state.removed.toSet()
    val incoming = restored.filterNot { it.id in gone }.distinctBy { it.id }
    val ids = incoming.map { it.id }.toSet()
    val merged = (state.notes.filterNot { it.id in ids } + incoming)
        .sortedBy { it.atMillis }
    return state.copy(notes = merged.takeLast(MAX_RECALL_NOTES))
}

/**
 * Active recall at the end of every focus block (2026-11-12, the user's
 * request): the timer asks what was just learned, and the answers wait on the
 * Flashcards screen to be turned into cards. On this device only.
 */
@Singleton
class RecallStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = JsonFile(
        file = { File(context.filesDir, "recall_notes.json") },
        serializer = RecallState.serializer(),
        empty = RecallState(),
        readable = { context.getSystemService(android.os.UserManager::class.java).isUserUnlocked }
    )

    val state: StateFlow<RecallState> = store.value

    suspend fun load(): RecallState = store.read()

    /** A focus block on [subject] just ended verified: ask for its recall. */
    suspend fun ask(subject: String, epochDay: Long, now: Long = System.currentTimeMillis()) {
        store.update { it.copy(pending = PendingRecall(subject, epochDay, now)) }
    }

    suspend fun answer(text: String, now: Long = System.currentTimeMillis()) {
        store.update { withRecall(it, text, UUID.randomUUID().toString(), now) }
    }

    /** Merges the notes of a JSON backup into this device's. */
    suspend fun restore(notes: List<RecallNote>) {
        if (notes.isNotEmpty()) store.update { withRestoredNotes(it, notes) }
    }

    suspend fun skip() {
        store.update { it.copy(pending = null) }
    }

    suspend fun remove(id: String) {
        store.update { withoutNote(it, id) }
    }
}
