package com.ogenrwotdaniel.wakeproof.wear

import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event sessions on the watch's own timer: the exact-plan copy, the last block, persistence. */
class WatchEventSessionTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val t0 = LocalDate.of(2026, 11, 4).atTime(14, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val minute = 60_000L
    private var ids = 0
    private fun nextId() = "block-${++ids}"

    /**
     * total minutes -> (focus blocks, last block minutes). The SAME table is in
     * the phone's `ExactSessionTest`, which pins the original `exactSessionPlan`
     * this one is a hand-kept copy of: change either and one side fails.
     */
    private val sharedVectors = listOf(
        3 to (1 to 10), 10 to (1 to 10), 15 to (1 to 15), 25 to (1 to 25), 34 to (1 to 34),
        35 to (1 to 35), 40 to (2 to 10), 50 to (2 to 20), 55 to (2 to 25), 62 to (2 to 32),
        85 to (3 to 25), 90 to (3 to 30), 120 to (4 to 30), 185 to (6 to 35)
    )

    @Test
    fun `the exact plan copy matches the phone's vectors`() {
        for ((total, expected) in sharedVectors) {
            val plan = WatchTimer.exactSessionPlan(total)
            assertEquals("total $total", expected, plan.focusBlocks to plan.lastFocusMinutes)
        }
    }

    @Test
    fun `every length from ten minutes to five hours comes out exact`() {
        for (total in WatchTimer.MIN_EXACT_SESSION_MINUTES..300) {
            val plan = WatchTimer.exactSessionPlan(total)
            assertEquals("total $total", total, (plan.focusBlocks - 1) * 30 + plan.lastFocusMinutes)
            assertTrue("total $total", plan.lastFocusMinutes >= WatchTimer.MIN_EXACT_SESSION_MINUTES)
        }
    }

    @Test
    fun `only the last block takes the trimmed length, as on the phone`() {
        assertEquals(1_500, WatchTimer.focusSecondsForBlock(1, 3, 1_500, 600))
        assertEquals(600, WatchTimer.focusSecondsForBlock(3, 3, 1_500, 600))
        assertEquals(1_500, WatchTimer.focusSecondsForBlock(3, 0, 1_500, 600))
    }

    /** Runs [start] to its end; returns (when it ended, the blocks credited, the phases in order). */
    private fun runToEnd(start: WatchTimerState): Triple<Long, List<FocusBlock>, List<WatchPhase>> {
        var s = start
        val blocks = mutableListOf<FocusBlock>()
        val phases = mutableListOf(s.phase)
        var now = t0
        while (true) {
            now = s.phaseEndsAt
            val a = WatchTimer.advance(s, now, utc, ::nextId)
            a.completed?.let { blocks += it }
            s = a.state
            if (a.sessionFinished) return Triple(now, blocks, phases)
            phases += s.phase
        }
    }

    @Test
    fun `an hour and two minutes runs 25, a break, then a stretched 32`() {
        val start = WatchTimer.startExact(62, "Calculus", t0)
        assertEquals(t0 + 25 * minute, start.phaseEndsAt)
        assertEquals(t0 + 62 * minute, WatchSessionBeacon.sessionEndsAt(start, t0))
        val (end, blocks, phases) = runToEnd(start)
        assertEquals(t0 + 62 * minute, end)
        assertEquals(listOf(25, 32), blocks.map { it.minutes })
        assertEquals(listOf(WatchPhase.FOCUS, WatchPhase.SHORT_BREAK, WatchPhase.FOCUS), phases)
        assertEquals(57, start.focusMinutesThrough(2))
    }

    @Test
    fun `an exact session never takes a long break`() {
        val (end, blocks, phases) = runToEnd(WatchTimer.startExact(185, null, t0))
        assertEquals(t0 + 185 * minute, end)
        assertEquals(listOf(25, 25, 25, 25, 25, 35), blocks.map { it.minutes })
        assertFalse(WatchPhase.LONG_BREAK in phases)
    }

    @Test
    fun `a short event is one block of its own length`() {
        val start = WatchTimer.startExact(15, null, t0)
        assertEquals(t0 + 15 * minute, start.phaseEndsAt)
        val (end, blocks, _) = runToEnd(start)
        assertEquals(t0 + 15 * minute, end)
        assertEquals(listOf(15), blocks.map { it.minutes })
    }

    @Test
    fun `an ordinary session's last block is a full one`() {
        val s = WatchTimer.start(WatchDurations(50, 10, 20), null, t0, WatchSession.TWO_HOURS)
        assertEquals(50 * 60, s.lastFocusSeconds)
        val (_, blocks, _) = runToEnd(s)
        assertTrue(blocks.all { it.minutes == 50 })
    }

    @Test
    fun `the beacon counts a trimmed last block from mid-break`() {
        // In the break after block 1 of 2, 5 min left, last block 20 min.
        val state = WatchTimer.startExact(50, null, t0).copy(
            phase = WatchPhase.SHORT_BREAK, phaseEndsAt = t0 + 5 * minute, completedFocus = 1
        )
        assertEquals(t0 + 25 * minute, WatchSessionBeacon.sessionEndsAt(state, t0))
    }

    @Test
    fun `event eligibility follows the phone's rules`() {
        // Not started: the whole event. Started: what is left. Over or < 10 min: none.
        assertEquals(85, WatchTimer.eventFocusMinutes(t0 + 10 * minute, t0 + 95 * minute, t0))
        assertEquals(65, WatchTimer.eventFocusMinutes(t0 - 20 * minute, t0 + 65 * minute, t0))
        assertNull(WatchTimer.eventFocusMinutes(t0 - 60 * minute, t0, t0))
        assertNull(WatchTimer.eventFocusMinutes(t0 - 60 * minute, t0 + 9 * minute, t0))
        assertEquals(10, WatchTimer.eventFocusMinutes(t0, t0 + 10 * minute, t0))
    }

    @Test
    fun `lastFocusSeconds survives the store`() {
        val prefs = FakePrefs()
        val state = WatchTimer.startExact(62, "Physics", t0)
        WatchTimerStore.write(prefs, state)
        assertEquals(state, WatchTimerStore.read(prefs))
        assertEquals(32 * 60, WatchTimerStore.read(prefs).lastFocusSeconds)
    }

    @Test
    fun `a state stored before event sessions reads its last block as a full one`() {
        val prefs = FakePrefs()
        WatchTimerStore.write(prefs, WatchTimer.start(WatchDurations(40, 5, 15), null, t0))
        prefs.values.remove("last_focus_seconds")
        val read = WatchTimerStore.read(prefs)
        assertEquals(40 * 60, read.lastFocusSeconds)
        assertEquals(40 * 60, WatchTimerStore.lastFocusDefault(WatchDurations(40, 5, 15)))
    }

    @Test
    fun `the idle state stores nothing and reads back idle`() {
        val prefs = FakePrefs()
        WatchTimerStore.write(prefs, WatchTimerState())
        assertTrue(prefs.values.isEmpty())
        assertEquals(WatchTimerState(), WatchTimerStore.read(prefs))
    }
}

/** In-memory [SharedPreferences]: an interface, so no Android runtime is needed. */
internal class FakePrefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
