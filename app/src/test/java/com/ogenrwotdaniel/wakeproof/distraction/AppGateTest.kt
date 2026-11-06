package com.ogenrwotdaniel.wakeproof.distraction

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGateTest {

    @Test
    fun `the capped app is back in front after a call that never paused it`() {
        val fold = ResumedActivities()
        fold.onResumed("com.instagram.android", "Main", at = 1_000)
        // Answered from a heads-up: the call screen resumes, Instagram never pauses.
        fold.onResumed("com.samsung.android.incallui", "InCall", at = 2_000)
        assertEquals("com.samsung.android.incallui", fold.foreground())
        // Call ends: only the call screen leaves. No fresh resume for Instagram.
        fold.onLeft("com.samsung.android.incallui", "InCall")
        assertEquals("com.instagram.android", fold.foreground())
    }

    @Test
    fun `a trampolining app stays in front when its first activity pauses late`() {
        val fold = ResumedActivities()
        fold.onResumed("com.whatsapp", "Main", at = 1_000)
        fold.onResumed("com.whatsapp", "Home", at = 1_010)
        fold.onLeft("com.whatsapp", "Main")
        assertEquals("com.whatsapp", fold.foreground())
    }

    @Test
    fun `nothing resumed means nothing known to be in front`() {
        val fold = ResumedActivities()
        fold.onResumed("a", "A", at = 1)
        fold.onLeft("a", "A")
        assertNull(fold.foreground())
    }

    @Test
    fun `social, video and messaging apps are gated, a notes app is not`() {
        assertTrue(AppGate.isGated("com.whatsapp", ApplicationInfo.CATEGORY_UNDEFINED, null))
        assertTrue(AppGate.isGated("x.y", ApplicationInfo.CATEGORY_SOCIAL, null))
        assertTrue(AppGate.isGated("x.y", ApplicationInfo.CATEGORY_VIDEO, null))
        assertFalse(
            AppGate.isGated("com.samsung.android.app.notes", ApplicationInfo.CATEGORY_PRODUCTIVITY, null)
        )
        assertFalse(
            AppGate.isGated("com.google.android.apps.bard", ApplicationInfo.CATEGORY_PRODUCTIVITY, null)
        )
    }

    @Test
    fun `the default sms app is never gated, even when it calls itself social`() {
        assertFalse(AppGate.isGated("sms.app", ApplicationInfo.CATEGORY_SOCIAL, "sms.app"))
        assertFalse(
            AppGate.isGated("com.samsung.android.messaging", ApplicationInfo.CATEGORY_UNDEFINED, null)
        )
    }

    @Test
    fun `every messenger and the Google Discover app are gated`() {
        assertTrue(AppGate.isGated("com.whatsapp.w4b", ApplicationInfo.CATEGORY_UNDEFINED, null))
        assertTrue(AppGate.isGated("org.telegram.messenger", ApplicationInfo.CATEGORY_UNDEFINED, null))
        assertTrue(AppGate.isGated("com.kakao.talk", ApplicationInfo.CATEGORY_UNDEFINED, null))
        assertTrue(AppGate.isGated("com.whatsapp", ApplicationInfo.CATEGORY_UNDEFINED, null))
        assertTrue(
            AppGate.isGated(
                "com.google.android.googlequicksearchbox",
                ApplicationInfo.CATEGORY_UNDEFINED,
                null
            )
        )
    }

    @Test
    fun `essential apps are never walled, even mid-session`() {
        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = true, focusSessionActive = true, onFocusList = false,
                capSpent = true, gated = true, outsideWindow = true
            )
        )
    }

    @Test
    fun `a focus session walls anything off the list`() {
        assertEquals(
            GateVerdict.FOCUS_WALL,
            gateVerdict(
                essential = false, focusSessionActive = true, onFocusList = false,
                capSpent = false, gated = true
            )
        )
        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = true, onFocusList = true,
                capSpent = false, gated = false
            )
        )
    }

    @Test
    fun `a spent cap outranks the window, and inside the window gated apps open directly`() {
        assertEquals(
            GateVerdict.CAP_WALL,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = true, gated = true, outsideWindow = true
            )
        )
        assertEquals(
            GateVerdict.WINDOW_WALL,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, outsideWindow = true
            )
        )
        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, outsideWindow = false
            )
        )
    }

    @Test
    fun `holdSuspended holds walled or forced apps and spares neverSuspend`() {
        assertFalse(holdSuspended(neverSuspend = false, verdict = GateVerdict.ALLOW))
        assertTrue(holdSuspended(neverSuspend = false, verdict = GateVerdict.WINDOW_WALL))
        assertTrue(holdSuspended(neverSuspend = false, verdict = GateVerdict.ALLOW, forced = true))
        assertFalse(holdSuspended(neverSuspend = true, verdict = GateVerdict.WINDOW_WALL, forced = true))
        assertTrue(AppGate.PLAY_STORE in AppGate.NEVER_SUSPEND)
        assertTrue(AppGate.GOOGLE_APP in AppGate.NEVER_SUSPEND)
    }

    @Test
    fun `hosted Gemini activities inside Google app resolve to Gemini while Discover and SilkPane stay Google`() {
        val fold = ResumedActivities()
        fold.onResumed(
            "com.google.android.apps.bard",
            "com.google.android.apps.bard.shellapp.BardEntryPointActivity",
            at = 1_000
        )
        fold.onLeft(
            "com.google.android.apps.bard",
            "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
        )
        fold.onResumed(
            "com.google.android.googlequicksearchbox",
            "com.google.apps.tiktok.nav.gateway.GatewayActivity",
            at = 1_050
        )
        fold.onLeft(
            "com.google.android.googlequicksearchbox",
            "com.google.apps.tiktok.nav.gateway.GatewayActivity"
        )
        fold.onResumed(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity",
            at = 1_100
        )
        assertEquals(AppGate.GEMINI_APP, fold.foreground())

        // Opening the in-app search browser or Discover from Gemini switches foreground to GOOGLE_APP.
        fold.onLeft(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity"
        )
        fold.onResumed(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.search.googleapp.silkpane.SilkPaneActivity",
            at = 2_000
        )
        assertEquals(AppGate.GOOGLE_APP, fold.foreground())
    }
}
