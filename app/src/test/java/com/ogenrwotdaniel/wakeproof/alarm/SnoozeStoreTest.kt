package com.ogenrwotdaniel.wakeproof.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class SnoozeStoreTest {

    private val today = 20_318L

    @Test
    fun `no entry means nothing has been snoozed`() {
        assertEquals(0, snoozesTakenOn(null, today))
    }

    @Test
    fun `today's count is spent`() {
        assertEquals(2, snoozesTakenOn(encodeSnoozeCount(today, 2), today))
    }

    @Test
    fun `yesterday's count is stale, not spent`() {
        // The allowance renews at midnight with nothing having to run to reset it.
        assertEquals(0, snoozesTakenOn(encodeSnoozeCount(today - 1, MAX_SNOOZES), today))
    }

    @Test
    fun `a count from the future is not today's either`() {
        // A clock knocked forward and back would otherwise leave an entry that
        // outlives every real day and permanently withholds the allowance.
        assertEquals(0, snoozesTakenOn(encodeSnoozeCount(today + 3, MAX_SNOOZES), today))
    }

    @Test
    fun `unreadable entries hand the allowance back`() {
        listOf("", ":", "not-a-day:1", "$today:", "$today:abc", "$today", "$today:-4")
            .forEach { assertEquals("stored=$it", 0, snoozesTakenOn(it, today)) }
    }

    private companion object {
        const val MAX_SNOOZES = SnoozeStore.MAX_PER_DAY
    }
}
