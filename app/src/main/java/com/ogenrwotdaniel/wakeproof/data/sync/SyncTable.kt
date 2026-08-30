package com.ogenrwotdaniel.wakeproof.data.sync

import com.google.firebase.firestore.DocumentSnapshot
import com.ogenrwotdaniel.wakeproof.alarm.AlarmDays
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLogDao
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.Exam
import com.ogenrwotdaniel.wakeproof.data.db.ExamDao
import com.ogenrwotdaniel.wakeproof.data.db.Flashcard
import com.ogenrwotdaniel.wakeproof.data.db.FlashcardDao
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThought
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThoughtDao
import com.ogenrwotdaniel.wakeproof.data.db.SyncColumns
import com.ogenrwotdaniel.wakeproof.data.db.SyncDao
import com.ogenrwotdaniel.wakeproof.data.db.SyncableEntity

/**
 * Everything the sync engine needs to know about one table, so the engine itself
 * stays table-agnostic: where the rows live in Firestore, how to get them in and
 * out of a document, and how to graft a downloaded row onto the local row's id.
 *
 * [withLocalId] exists because the primary key is deliberately *not* synced —
 * a downloaded row has to inherit whatever rowid this device already gave it,
 * otherwise updating an alarm would orphan its scheduled AlarmManager intent.
 */
class SyncTable<T : SyncableEntity>(
    val collection: String,
    val dao: SyncDao<T>,
    private val fields: (T) -> Map<String, Any?>,
    private val decode: (DocumentSnapshot) -> T?,
    val withLocalId: (T, Long) -> T
) {
    /** The full document body: the table's own fields plus the sync bookkeeping. */
    fun encode(entity: T): Map<String, Any?> = fields(entity) + mapOf(
        SyncColumns.SYNC_ID to entity.syncId,
        SyncColumns.UPDATED_AT to entity.updatedAt,
        SyncColumns.DELETED to entity.deleted
    )

    /**
     * Rebuilds an entity from a document, or null if the document is unreadable —
     * a field renamed by a future version, or a partial write. Returning null skips
     * that one row instead of failing the whole sync pass.
     */
    fun decodeOrNull(doc: DocumentSnapshot): T? = runCatching { decode(doc) }.getOrNull()
}

/** Firestore hands integers back as [Long] and never as [Int]. */
private fun DocumentSnapshot.int(field: String): Int = getLong(field)?.toInt() ?: 0

private fun DocumentSnapshot.long(field: String): Long = getLong(field) ?: 0L

private fun DocumentSnapshot.bool(field: String): Boolean = getBoolean(field) ?: false

private fun DocumentSnapshot.str(field: String): String = getString(field).orEmpty()

/** The three sync columns, read back off a document. */
private class Meta(doc: DocumentSnapshot) {
    val syncId: String = doc.getString(SyncColumns.SYNC_ID) ?: doc.id
    val updatedAt: Long = doc.long(SyncColumns.UPDATED_AT)
    val deleted: Boolean = doc.bool(SyncColumns.DELETED)
}

/**
 * Enum columns travel as their `name`. An unknown name means the row was written
 * by a newer version of the app on another device, so the row is dropped rather
 * than coerced into the wrong bucket.
 */
private inline fun <reified E : Enum<E>> DocumentSnapshot.enumOrNull(field: String): E? =
    getString(field)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

/**
 * The five synced tables. Order matters only for readability — the engine treats
 * them independently, so one table failing does not hold up the rest.
 */
fun syncTables(
    alarms: AlarmDao,
    logs: CompletionLogDao,
    cards: FlashcardDao,
    exams: ExamDao,
    thoughts: ParkedThoughtDao
): List<SyncTable<out SyncableEntity>> = listOf(
    SyncTable(
        collection = "alarms",
        dao = alarms,
        fields = {
            mapOf(
                "type" to it.type.name,
                "hour" to it.hour,
                "minute" to it.minute,
                "enabled" to it.enabled,
                "daysMask" to it.daysMask,
                "label" to it.label,
                "lastCompletedEpochDay" to it.lastCompletedEpochDay
            )
        },
        decode = { doc ->
            val meta = Meta(doc)
            doc.enumOrNull<AlarmType>("type")?.let { type ->
                Alarm(
                    type = type,
                    hour = doc.int("hour"),
                    minute = doc.int("minute"),
                    enabled = doc.bool("enabled"),
                    // Absent on documents written before day-of-week alarms
                    // existed. Those alarms rang daily, so that is what the
                    // missing field means — not "never".
                    daysMask = doc.getLong("daysMask")?.toInt() ?: AlarmDays.EVERY_DAY,
                    label = doc.str("label"),
                    lastCompletedEpochDay = doc.getLong("lastCompletedEpochDay"),
                    syncId = meta.syncId,
                    updatedAt = meta.updatedAt,
                    deleted = meta.deleted,
                    pending = false
                )
            }
        },
        withLocalId = { entity, id -> entity.copy(id = id) }
    ),
    SyncTable(
        collection = "completion_logs",
        dao = logs,
        fields = {
            mapOf(
                "epochDay" to it.epochDay,
                "kind" to it.kind.name,
                "status" to it.status.name,
                "amount" to it.amount,
                "subject" to it.subject
            )
        },
        decode = { doc ->
            val meta = Meta(doc)
            val kind = doc.enumOrNull<GoalKind>("kind")
            val status = doc.enumOrNull<CompletionStatus>("status")
            if (kind == null || status == null) {
                null
            } else {
                CompletionLog(
                    epochDay = doc.long("epochDay"),
                    kind = kind,
                    status = status,
                    amount = doc.int("amount"),
                    // alarmId is deliberately not synced: it is a local rowid and
                    // would point at a different alarm (or none) on the other device.
                    alarmId = null,
                    subject = doc.getString("subject"),
                    syncId = meta.syncId,
                    updatedAt = meta.updatedAt,
                    deleted = meta.deleted,
                    pending = false
                )
            }
        },
        withLocalId = { entity, id -> entity.copy(id = id) }
    ),
    SyncTable(
        collection = "flashcards",
        dao = cards,
        fields = {
            mapOf(
                "subject" to it.subject,
                "front" to it.front,
                "back" to it.back,
                "easiness" to it.easiness,
                "repetitions" to it.repetitions,
                "intervalDays" to it.intervalDays,
                "dueEpochDay" to it.dueEpochDay,
                "createdEpochDay" to it.createdEpochDay
            )
        },
        decode = { doc ->
            val meta = Meta(doc)
            Flashcard(
                subject = doc.str("subject"),
                front = doc.str("front"),
                back = doc.str("back"),
                easiness = doc.getDouble("easiness") ?: 2.5,
                repetitions = doc.int("repetitions"),
                intervalDays = doc.int("intervalDays"),
                dueEpochDay = doc.long("dueEpochDay"),
                createdEpochDay = doc.long("createdEpochDay"),
                syncId = meta.syncId,
                updatedAt = meta.updatedAt,
                deleted = meta.deleted,
                pending = false
            )
        },
        withLocalId = { entity, id -> entity.copy(id = id) }
    ),
    SyncTable(
        collection = "exams",
        dao = exams,
        fields = { mapOf("name" to it.name, "epochDay" to it.epochDay) },
        decode = { doc ->
            val meta = Meta(doc)
            Exam(
                name = doc.str("name"),
                epochDay = doc.long("epochDay"),
                syncId = meta.syncId,
                updatedAt = meta.updatedAt,
                deleted = meta.deleted,
                pending = false
            )
        },
        withLocalId = { entity, id -> entity.copy(id = id) }
    ),
    SyncTable(
        collection = "parked_thoughts",
        dao = thoughts,
        fields = {
            mapOf(
                "text" to it.text,
                "createdAtMillis" to it.createdAtMillis,
                "done" to it.done
            )
        },
        decode = { doc ->
            val meta = Meta(doc)
            ParkedThought(
                text = doc.str("text"),
                createdAtMillis = doc.long("createdAtMillis"),
                done = doc.bool("done"),
                syncId = meta.syncId,
                updatedAt = meta.updatedAt,
                deleted = meta.deleted,
                pending = false
            )
        },
        withLocalId = { entity, id -> entity.copy(id = id) }
    )
)
