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

    /** Focus minutes per subject since [fromEpochDay] (null subject = untagged). */
    fun observeFocusBySubject(fromEpochDay: Long) = dao.observeFocusBySubject(fromEpochDay)

    fun observeSince(fromEpochDay: Long): Flow<List<CompletionLog>> = dao.observeSince(fromEpochDay)

    fun observeToday(kind: GoalKind): Flow<List<CompletionLog>> =
        dao.observeForDay(LocalDate.now().toEpochDay(), kind)

    suspend fun getAll(): List<CompletionLog> = dao.getAll()
}
