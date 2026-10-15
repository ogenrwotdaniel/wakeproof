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

    /** Focus minutes per subject since [fromEpochDay] (null subject = untagged). */
    fun observeFocusBySubject(fromEpochDay: Long) = dao.observeFocusBySubject(fromEpochDay)

    fun observeSince(fromEpochDay: Long): Flow<List<CompletionLog>> = dao.observeSince(fromEpochDay)

    fun observeToday(kind: GoalKind): Flow<List<CompletionLog>> =
        dao.observeForDay(LocalDate.now().toEpochDay(), kind)

    suspend fun getAll(): List<CompletionLog> = dao.getAll()
}
