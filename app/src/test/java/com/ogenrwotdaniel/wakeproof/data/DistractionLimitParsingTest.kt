package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository.Companion.parseLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistractionLimitParsingTest {

    @Test
    fun `limits stored before modes existed stay on nudge`() {
        // The whole point: an upgrade must never turn an existing soft cap into a
        // hard block the user never asked for and cannot undo until midnight.
        val parsed = parseLimits(setOf("com.instagram.android|30"))
        assertEquals(AppLimit(30, LimitMode.NUDGE), parsed["com.instagram.android"])
    }

    @Test
    fun `mode round-trips`() {
        val parsed = parseLimits(setOf("com.x|45|BLOCK", "com.y|20|NUDGE"))
        assertEquals(AppLimit(45, LimitMode.BLOCK), parsed["com.x"])
        assertEquals(AppLimit(20, LimitMode.NUDGE), parsed["com.y"])
    }

    @Test
    fun `an unknown mode name falls back to nudge rather than blocking`() {
        // Forward compatibility that fails safe: garbage must not block an app.
        val parsed = parseLimits(setOf("com.x|45|OBLITERATE"))
        assertEquals(LimitMode.NUDGE, parsed.getValue("com.x").mode)
    }

    @Test
    fun `malformed and non-positive entries are dropped`() {
        val parsed = parseLimits(
            setOf("", "|30|BLOCK", "com.a", "com.b|abc", "com.c|0|BLOCK", "com.d|-5")
        )
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `entries are keyed by the full package name`() {
        // "com.a" must not shadow "com.ab" — the old prefix matching could.
        val parsed = parseLimits(setOf("com.a|10|NUDGE", "com.ab|20|BLOCK"))
        assertEquals(10, parsed.getValue("com.a").minutes)
        assertEquals(20, parsed.getValue("com.ab").minutes)
        assertNull(parsed["com.abc"])
    }
}
