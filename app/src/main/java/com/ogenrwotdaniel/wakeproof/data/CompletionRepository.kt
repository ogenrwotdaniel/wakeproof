package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLogDao
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Records goal completions and exposes them for stats/streaks. */
@Singleton
class CompletionRepository @Inject constructor(private val dao: CompletionLogDao) {

    suspend fun log(
        kind: GoalKind,
        status: CompletionStatus,
        amount: Int = 0,
        alarmId: Long? = null,
        subject: String? = null
    ) {
        dao.insert(
            CompletionLog(
                epochDay = LocalDate.now().toEpochDay(),
                kind = kind,
                status = status,
                amount = amount,
                alarmId = alarmId,
                subject = subject
            )
        )
    }

    /**
     * Records a focus block of a session that other devices may be running too
     * ([com.ogenrwotdaniel.wakeproof.pomodoro.SharedSession]), under the id they
     * all give it. The first device to finish the block writes the row; a later
     * one changes it only to mark it SKIPPED: a tree withered on any device that
     * ran the block spoils it, so the strictest record stands.
     *
     * Sync merges by the newer `updatedAt` and knows nothing of status, and the
     * other device's VERIFIED copy of the same block is stamped within seconds
     * of this one. So a SKIPPED row is stamped [SKIPPED_LEAD_MILLIS] ahead: it
     * wins that merge whichever copy uploads first.
     */
    suspend fun logFocusBlock(syncId: String, status: CompletionStatus, minutes: Int, subject: String?) {
        val now = System.currentTimeMillis()
        val skipped = status == CompletionStatus.SKIPPED
        val existing = dao.findBySyncId(syncId) ?: run {
            val inserted = runCatching {
                dao.insert(
                    CompletionLog(
                        epochDay = LocalDate.now().toEpochDay(),
                        kind = GoalKind.FOCUS,
                        status = status,
                        amount = minutes,
                        subject = subject,
                        syncId = syncId,
                        updatedAt = if (skipped) now + SKIPPED_LEAD_MILLIS else now
                    )
                )
            }
            if (inserted.isSuccess) return
            // The watch's copy of this block landed between the look and the insert
            // (the sync id is unique): it is here now, and is judged like any other.
            dao.findBySyncId(syncId) ?: throw inserted.exceptionOrNull()!!
        }
        if (skipped && existing.status != CompletionStatus.SKIPPED && !existing.deleted) {
            dao.update(
                existing.copy(
                    status = CompletionStatus.SKIPPED,
                    updatedAt = maxOf(existing.updatedAt, now) + SKIPPED_LEAD_MILLIS,
                    pending = true
                )
            )
        }
    }

    /**
     * Records a focus block that was timed somewhere else — the watch — under
     * the id it was given there, and reports whether it was new.
     *
     * Keyed on [syncId] so delivery is idempotent: the Data Layer may hand the
     * same block over twice, and cloud sync may already have brought it in from
     * another device. A tombstone counts as present, so a block the user deleted
     * is not resurrected by a late redelivery.
     */
    suspend fun importFocusBlock(syncId: String, epochDay: Long, minutes: Int, subject: String?): Boolean {
        if (dao.findBySyncId(syncId) != null) return false
        dao.insert(
            CompletionLog(
                epochDay = epochDay,
                kind = GoalKind.FOCUS,
                status = CompletionStatus.VERIFIED,
                amount = minutes,
                subject = subject,
                syncId = syncId
            )
        )
        return true
    }

    /** Every focus block id since [fromEpochDay], tombstones included. */
    suspend fun focusSyncIdsSince(fromEpochDay: Long): List<String> = dao.focusSyncIdsSince(fromEpochDay)

    /** Focus minutes per subject since [fromEpochDay] (null subject = untagged). */
    fun observeFocusBySubject(fromEpochDay: Long) = dao.observeFocusBySubject(fromEpochDay)

    fun observeSince(fromEpochDay: Long): Flow<List<CompletionLog>> = dao.observeSince(fromEpochDay)

    fun observeToday(kind: GoalKind): Flow<List<CompletionLog>> =
        dao.observeForDay(LocalDate.now().toEpochDay(), kind)

    suspend fun getAll(): List<CompletionLog> = dao.getAll()

    private companion object {
        /** Far more than two devices' clocks differ by, far less than anyone waits to delete a row. */
        const val SKIPPED_LEAD_MILLIS = 10 * 60_000L
    }
}
