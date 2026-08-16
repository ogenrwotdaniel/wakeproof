package com.ogenrwotdaniel.wakeproof.alarm

import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmInitializerTest {

    private val enabled = Alarm(id = 1, type = AlarmType.PUSHUP, hour = 5, minute = 0, enabled = true)
    private val disabled = Alarm(id = 2, type = AlarmType.WATER, hour = 8, minute = 50, enabled = false)
    private val deleted = Alarm(
        id = 3,
        type = AlarmType.CUSTOM,
        hour = 12,
        minute = 0,
        enabled = true,
        deleted = true
    )

    @Test
    fun `schedules only live alarms and cancels the rest`() {
        val plan = reconcilePlan(
            alarms = listOf(enabled, disabled, deleted),
            mirroredIds = setOf(1L, 2L, 3L)
        )

        assertEquals(listOf(enabled), plan.toSchedule)
        assertEquals(listOf(disabled, deleted), plan.toCancel)
        assertTrue(plan.orphanIds.isEmpty())
    }

    @Test
    fun `a mirrored id with no row left is an orphan to cancel`() {
        // 99 is what a retired starter alarm leaves behind: hard-deleted from the
        // table, still armed, and invisible to every other reconciliation path.
        val plan = reconcilePlan(alarms = listOf(enabled), mirroredIds = setOf(1L, 99L))

        assertEquals(listOf(99L), plan.orphanIds)
        assertEquals(listOf(enabled), plan.toSchedule)
    }

    @Test
    fun `a tombstoned alarm is cancelled by row, not treated as an orphan`() {
        val plan = reconcilePlan(alarms = listOf(deleted), mirroredIds = setOf(3L))

        assertEquals(listOf(deleted), plan.toCancel)
        assertTrue(plan.orphanIds.isEmpty())
    }

    @Test
    fun `an alarm that was never mirrored still gets scheduled`() {
        val plan = reconcilePlan(alarms = listOf(enabled), mirroredIds = emptySet())

        assertEquals(listOf(enabled), plan.toSchedule)
        assertTrue(plan.toCancel.isEmpty())
        assertTrue(plan.orphanIds.isEmpty())
    }
}
