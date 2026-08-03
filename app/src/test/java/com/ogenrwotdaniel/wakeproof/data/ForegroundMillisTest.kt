package com.ogenrwotdaniel.wakeproof.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundMillisTest {

    private fun resumed(pkg: String, cls: String, at: Long) =
        ForegroundEvent(pkg, cls, resumed = true, timestamp = at)

    private fun paused(pkg: String, cls: String, at: Long) =
        ForegroundEvent(pkg, cls, resumed = false, timestamp = at)

    @Test
    fun `single-activity app measures its session`() {
        val millis = foldForegroundMillis(
            listOf(
                resumed("com.instagram.android", "MainActivity", 0),
                paused("com.instagram.android", "MainActivity", 60_000)
            ),
            now = 120_000
        )
        assertEquals(60_000L, millis["com.instagram.android"])
    }

    @Test
    fun `a trampolining app is not zeroed by the launcher activity's trailing events`() {
        // The WhatsApp Business shape, taken from a real event dump: Main resumes,
        // hands off to HomeActivity, and only then emits its own PAUSED/STOPPED.
        // Tracking one timestamp per package let those close HomeActivity's live
        // session, so 10 minutes of use measured as ~0 and the cap never tripped.
        val pkg = "com.whatsapp.w4b"
        val millis = foldForegroundMillis(
            listOf(
                resumed(pkg, "com.whatsapp.Main", 0),
                paused(pkg, "com.whatsapp.Main", 100),
                resumed(pkg, "com.whatsapp.home.ui.HomeActivity", 150),
                paused(pkg, "com.whatsapp.Main", 200),   // STOPPED for the old one
                paused(pkg, "com.whatsapp.home.ui.HomeActivity", 600_000)
            ),
            now = 700_000
        )
        // 100 ms in Main, then ~10 minutes in HomeActivity.
        assertEquals(100L + (600_000L - 150L), millis[pkg])
    }

    @Test
    fun `navigating between activities inside one app stays one continuous session`() {
        val pkg = "com.whatsapp.w4b"
        val millis = foldForegroundMillis(
            listOf(
                resumed(pkg, "HomeActivity", 0),
                resumed(pkg, "Conversation", 1_000),
                paused(pkg, "HomeActivity", 1_100),
                paused(pkg, "Conversation", 300_000)
            ),
            now = 400_000
        )
        // Foreground the whole time, so the gap must not be double-counted either.
        assertEquals(300_000L, millis[pkg])
    }

    @Test
    fun `a session still on screen counts up to now`() {
        val millis = foldForegroundMillis(
            listOf(resumed("com.x", "A", 0)),
            now = 45_000
        )
        assertEquals(45_000L, millis["com.x"])
    }

    @Test
    fun `duplicate pause and stop for the same activity is not counted twice`() {
        val millis = foldForegroundMillis(
            listOf(
                resumed("com.x", "A", 0),
                paused("com.x", "A", 10_000),
                paused("com.x", "A", 10_000)
            ),
            now = 60_000
        )
        assertEquals(10_000L, millis["com.x"])
    }

    @Test
    fun `separate visits add up`() {
        val millis = foldForegroundMillis(
            listOf(
                resumed("com.x", "A", 0),
                paused("com.x", "A", 10_000),
                resumed("com.x", "A", 50_000),
                paused("com.x", "A", 70_000)
            ),
            now = 80_000
        )
        assertEquals(30_000L, millis["com.x"])
    }

    @Test
    fun `apps are tracked independently`() {
        val millis = foldForegroundMillis(
            listOf(
                resumed("com.a", "A", 0),
                resumed("com.b", "B", 5_000),
                paused("com.a", "A", 5_000),
                paused("com.b", "B", 15_000)
            ),
            now = 20_000
        )
        assertEquals(5_000L, millis["com.a"])
        assertEquals(10_000L, millis["com.b"])
    }

    @Test
    fun `a pause with no matching resume is ignored rather than counted`() {
        // Happens at the start of the day: the session began before midnight.
        val millis = foldForegroundMillis(
            listOf(paused("com.x", "A", 10_000)),
            now = 20_000
        )
        assertEquals(null, millis["com.x"])
    }
}
