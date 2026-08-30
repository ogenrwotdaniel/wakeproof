package com.ogenrwotdaniel.wakeproof.data.backup

import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.Exam
import com.ogenrwotdaniel.wakeproof.data.db.Flashcard
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThought
import kotlinx.serialization.Serializable

/**
 * A whole-app backup, as one JSON document.
 *
 * ### Why this exists
 *
 * Nothing else preserves this data. `allowBackup` is deliberately `false`, so
 * Android's own backup never runs; the app is not installed from Play, so
 * Play's backup does not apply either; and cloud sync is opt-in, mirrors only
 * the tables it knows about, and is not a backup at all — it faithfully
 * replicates a deletion to every device. A lost or wiped phone therefore took
 * the streak history with it, and the streak is the thing the app is *for*.
 *
 * ### Why JSON of the entities, not a copy of the database file
 *
 * Copying `wakeproof.db` is tempting and wrong twice over. It needs the `-wal`
 * and `-shm` files copied atomically alongside it — the trap already documented
 * in CLAUDE.md, which cost an hour once — and a raw database restored into a
 * different schema version is either rejected or silently corrupt. A JSON
 * document of the entities survives a schema that has moved on, because a
 * missing field falls back to the entity's default.
 *
 * [version] is that fallback's safety net: it records what wrote the file, so a
 * future reader can tell "this predates field X" from "this file is damaged".
 */
@Serializable
data class BackupFile(
    val version: Int = CURRENT_BACKUP_VERSION,
    val createdAtMillis: Long = 0L,
    val alarms: List<Alarm> = emptyList(),
    val completionLogs: List<CompletionLog> = emptyList(),
    val flashcards: List<Flashcard> = emptyList(),
    val exams: List<Exam> = emptyList(),
    val parkedThoughts: List<ParkedThought> = emptyList()
) {
    val totalRows: Int
        get() = alarms.size + completionLogs.size + flashcards.size +
            exams.size + parkedThoughts.size
}

/**
 * Bumped when the *document* shape changes, not when the database does.
 *
 * 1 — first format: the five Room tables, tombstones included.
 */
const val CURRENT_BACKUP_VERSION = 1

/**
 * A stable, sortable filename so a folder of backups reads as a history.
 *
 * Date-first rather than name-first because the only question ever asked of a
 * pile of these is "which is the newest".
 */
fun backupFilename(isoDate: String): String = "wakeproof-backup-$isoDate.json"
