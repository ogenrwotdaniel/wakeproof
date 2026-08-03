package com.ogenrwotdaniel.wakeproof.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMotivationTest {

    private val motivation = OfflineMotivation()

    @Test
    fun `line always states the actual number`() {
        assertTrue(motivation.lineFor(Achievement.Pushups(17), rotation = 0).contains("17"))
        assertTrue(motivation.lineFor(Achievement.Water(450), rotation = 0).contains("450"))
    }

    @Test
    fun `no placeholder survives into the rendered line`() {
        val everything = (0L..40L).flatMap { rotation ->
            listOf(1, 9, 10, 24, 25, 100).map { motivation.lineFor(Achievement.Pushups(it), rotation) } +
                listOf(10, 299, 300, 599, 600, 2000).map {
                    motivation.lineFor(Achievement.Water(it), rotation)
                }
        }
        assertTrue(everything.none { it.contains("{n}") })
        assertTrue(everything.none { it.isBlank() })
    }

    @Test
    fun `effort tiers get different lines`() {
        // Same rotation, different magnitude: a 5-rep morning and a 40-rep
        // morning must not get the same congratulation.
        assertNotEquals(
            motivation.lineFor(Achievement.Pushups(5), rotation = 3),
            motivation.lineFor(Achievement.Pushups(40), rotation = 3)
        )
        assertNotEquals(
            motivation.lineFor(Achievement.Water(100), rotation = 3),
            motivation.lineFor(Achievement.Water(900), rotation = 3)
        )
    }

    @Test
    fun `tier boundaries land on the intended side`() {
        // 9 vs 10 and 24 vs 25 are the pushup tier edges.
        assertNotEquals(
            motivation.lineFor(Achievement.Pushups(9), rotation = 0),
            motivation.lineFor(Achievement.Pushups(10), rotation = 0)
        )
        assertNotEquals(
            motivation.lineFor(Achievement.Pushups(24), rotation = 0),
            motivation.lineFor(Achievement.Pushups(25), rotation = 0)
        )
        // 299 vs 300 and 599 vs 600 for water.
        assertNotEquals(
            motivation.lineFor(Achievement.Water(299), rotation = 0),
            motivation.lineFor(Achievement.Water(300), rotation = 0)
        )
        assertNotEquals(
            motivation.lineFor(Achievement.Water(599), rotation = 0),
            motivation.lineFor(Achievement.Water(600), rotation = 0)
        )
    }

    @Test
    fun `rotation exhausts the tier before any line repeats`() {
        // The point of rotating by date: consecutive mornings must not read the
        // same, and a line may only come back once the whole tier has been used.
        val seen = (0L..5L).map { motivation.lineFor(Achievement.Pushups(15), it) }
        assertEquals(seen.size, seen.toSet().size)
        // Wraps cleanly rather than drifting or throwing.
        assertEquals(seen[0], motivation.lineFor(Achievement.Pushups(15), rotation = 6))
    }

    @Test
    fun `a device clock set before 1970 still produces a line`() {
        // floorMod, not %, so a negative epoch day cannot index out of bounds.
        val line = motivation.lineFor(Achievement.Water(500), rotation = -3)
        assertTrue(line.contains("500"))
    }
}
