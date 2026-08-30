package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access to the [Alarm] table.
 *
 * Every read filters `deleted = 0`: an alarm deleted on the tablet arrives here
 * as a tombstone row, and an alarm that still showed in the list — or worse,
 * still got scheduled — would make the delete look like it never happened.
 */
@Dao
interface AlarmDao : SyncDao<Alarm> {
    /**
     * Every row, tombstones included.
     *
     * Filtering `deleted = 0` here would be a quiet data-loss bug rather
     * than a tidy-up: a restored device that has forgotten its tombstones
     * cannot tell "deleted" from "never seen", so the next sync would
     * resurrect every item the user had removed.
     */
    @Query("SELECT * FROM alarms")
    suspend fun backupAll(): List<Alarm>

    /** Restores rows verbatim, ids included, so references between tables survive. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(rows: List<Alarm>)

    @Query("SELECT * FROM alarms WHERE deleted = 0 ORDER BY hour, minute")
    fun observeAll(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE id = :id AND deleted = 0")
    fun observeById(id: Long): Flow<Alarm?>

    @Query("SELECT * FROM alarms WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1 AND deleted = 0")
    suspend fun getEnabled(): List<Alarm>

    @Query(
        "SELECT * FROM alarms WHERE type = :type AND deleted = 0 " +
            "ORDER BY hour, minute LIMIT 1"
    )
    suspend fun getFirstByType(type: AlarmType): Alarm?

    /**
     * Counts tombstones too, because this is what decides whether to seed the
     * starter alarms. Ignoring them would re-seed the defaults for a user who has
     * deliberately deleted every alarm — on the next app start, every time.
     */
    @Query("SELECT COUNT(*) FROM alarms")
    suspend fun count(): Int

    /** Soft delete, so the other devices learn the alarm is gone. */
    @Query("UPDATE alarms SET deleted = 1, pending = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Insert
    suspend fun insertAll(alarms: List<Alarm>)

    @Update
    suspend fun update(alarm: Alarm)

    /**
     * Completing an alarm does not promote a starter alarm into a real one — the
     * completion itself is already recorded in `completion_logs`, which syncs — so
     * a still-seeded row stays unqueued rather than uploading a default nobody chose.
     */
    @Query(
        "UPDATE alarms SET lastCompletedEpochDay = :epochDay, updatedAt = :now, " +
            "pending = CASE WHEN seeded = 1 THEN 0 ELSE 1 END WHERE id = :id"
    )
    suspend fun markCompleted(id: Long, epochDay: Long, now: Long = System.currentTimeMillis())

    /**
     * Tombstones included — the only read that wants them. Reconciling AlarmManager
     * has to see a deleted alarm in order to cancel the alarm it still has pending.
     */
    @Query("SELECT * FROM alarms")
    suspend fun getAllIncludingDeleted(): List<Alarm>

    /** Alarms that came from the account or the user, as opposed to the starter set. */
    @Query("SELECT COUNT(*) FROM alarms WHERE seeded = 0 AND deleted = 0")
    suspend fun countRealAlarms(): Int

    /** The starter alarms, read so their schedules can be cancelled before they go. */
    @Query("SELECT * FROM alarms WHERE seeded = 1")
    suspend fun seededAlarms(): List<Alarm>

    /**
     * Hard delete, not a tombstone: seeded rows are never uploaded, so no other
     * device has heard of them and there is nothing to announce.
     *
     * Cancel these alarms with [seededAlarms] BEFORE calling this. A tombstone
     * would keep them reconcilable; a hard delete does not, so once the rows are
     * gone nothing in the table can point at their still-armed pending intents.
     */
    @Query("DELETE FROM alarms WHERE seeded = 1")
    suspend fun deleteSeeded()

    @Query("SELECT * FROM alarms WHERE pending = 1 ORDER BY updatedAt LIMIT :limit")
    override suspend fun pendingUploads(limit: Int): List<Alarm>

    @Query("SELECT * FROM alarms WHERE syncId = :syncId")
    override suspend fun findBySyncId(syncId: String): Alarm?

    @Query("UPDATE alarms SET pending = 0 WHERE syncId = :syncId AND updatedAt = :updatedAt")
    override suspend fun clearPending(syncId: String, updatedAt: Long)
}
