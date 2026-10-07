package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ForestTest {

    private fun log(id: Long, day: Long, kind: GoalKind, status: CompletionStatus, amount: Int) =
        CompletionLog(id = id, epochDay = day, kind = kind, status = status, amount = amount)

    @Test
    fun `each focus block is a tree, withered ones excluded from the minutes`() {
        val logs = listOf(
            log(1, 100, GoalKind.FOCUS, CompletionStatus.VERIFIED, 25),
            log(2, 100, GoalKind.FOCUS, CompletionStatus.SKIPPED, 25),
            log(3, 100, GoalKind.FOCUS, CompletionStatus.VERIFIED, 50),
            log(4, 100, GoalKind.PUSHUP, CompletionStatus.VERIFIED, 15),
            log(5, 99, GoalKind.FOCUS, CompletionStatus.VERIFIED, 10)
        )
        val days = forestDays(logs, today = 100, days = 3)
        assertEquals(listOf(100L, 99L, 98L), days.map { it.epochDay })
        assertEquals(listOf("🌳", "🥀", "🌲"), days[0].trees)
        assertEquals(75, days[0].minutes)
        assertEquals(1, days[0].withered)
        assertEquals(listOf("🌿"), days[1].trees)
        assertEquals(emptyList<String>(), days[2].trees)
    }
}
