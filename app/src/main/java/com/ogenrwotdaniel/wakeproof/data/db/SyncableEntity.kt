package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Insert
import androidx.room.Update

/**
 * Columns every table that mirrors to the cloud carries.
 *
 * Room's `id` is an autoincrement rowid and therefore only meaningful on the
 * device that generated it — two devices will happily both mint `id = 7`. So
 * rows are identified across devices by [syncId] instead, and the local `id`
 * never leaves the device.
 *
 * The three remaining columns split two jobs that are easy to conflate:
 *
 * - [updatedAt] answers *"which version wins?"* on a conflict (last write wins).
 * - [pending] answers *"does this row still need uploading?"*.
 *
 * Keeping them separate is what stops an upload/download echo: a row that
 * arrives from the cloud is written with the remote [updatedAt] but
 * `pending = false`, so it is not immediately queued to be pushed straight back.
 *
 * [deleted] is a tombstone. A hard `DELETE` cannot be replicated — the other
 * device has no way to tell "deleted" from "never seen", and would resurrect
 * the row on its next push.
 */
interface SyncableEntity {
    /** Local rowid. Never uploaded — see the note above on why it cannot be. */
    val id: Long
    val syncId: String
    val updatedAt: Long
    val deleted: Boolean
    val pending: Boolean
}

/** Column names, shared by the entities, the migration and the Firestore mapper. */
object SyncColumns {
    const val SYNC_ID = "syncId"
    const val UPDATED_AT = "updatedAt"
    const val DELETED = "deleted"
    const val PENDING = "pending"

    /**
     * `ALTER TABLE` fragments used by the v3 -> v4 migration. Every default here
     * must match the `@ColumnInfo(defaultValue = ...)` on the entity field, or
     * Room's schema validation rejects the migrated database on open.
     */
    fun addColumnsTo(table: String): List<String> = listOf(
        "ALTER TABLE `$table` ADD COLUMN `$SYNC_ID` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `$table` ADD COLUMN `$UPDATED_AT` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `$table` ADD COLUMN `$DELETED` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `$table` ADD COLUMN `$PENDING` INTEGER NOT NULL DEFAULT 1",
        // randomblob is SQLite's only source of per-row uniqueness inside a plain
        // UPDATE; a Kotlin-side UUID would need one statement per existing row.
        "UPDATE `$table` SET `$SYNC_ID` = lower(hex(randomblob(16)))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_$SYNC_ID` " +
            "ON `$table` (`$SYNC_ID`)"
    )
}

/**
 * The five calls the sync engine makes against every synced table.
 *
 * [insertSynced] and [updateSynced] are inherited as-is — Room resolves `T` from
 * the concrete DAO's supertype. The three queries name a table, so each DAO has
 * to override them with its own `@Query`.
 */
interface SyncDao<T : SyncableEntity> {

    /** Rows changed locally since the last successful upload. */
    suspend fun pendingUploads(limit: Int = SYNC_BATCH): List<T>

    /** The local row a remote document refers to, or null if this device has never seen it. */
    suspend fun findBySyncId(syncId: String): T?

    /**
     * Clears the upload flag, but only if the row still holds the version that was
     * uploaded. The `updatedAt` guard is what makes an edit made *during* an
     * in-flight upload survive: that edit bumps `updatedAt`, so this no-ops and the
     * row stays queued for the next round instead of being silently dropped.
     */
    suspend fun clearPending(syncId: String, updatedAt: Long)

    @Insert
    suspend fun insertSynced(entity: T)

    @Update
    suspend fun updateSynced(entity: T)
}

/** Rows per table per sync pass — enough for a normal delta, small enough to stay cheap. */
const val SYNC_BATCH = 400
