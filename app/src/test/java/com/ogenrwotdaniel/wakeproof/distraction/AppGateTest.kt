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
    fun `social, video and messaging apps wait, a notes app does not`() {
        assertTrue(AppGate.isGated("com.whatsapp", ApplicationInfo.CATEGORY_UNDEFINED, null))
        assertTrue(AppGate.isGated("x.y", ApplicationInfo.CATEGORY_SOCIAL, null))
        assertTrue(AppGate.isGated("x.y", ApplicationInfo.CATEGORY_VIDEO, null))
        assertFalse(
            AppGate.isGated("com.samsung.android.app.notes", ApplicationInfo.CATEGORY_PRODUCTIVITY, null)
        )
    }

    @Test
    fun `the default sms app never waits, even when it calls itself social`() {
        assertFalse(AppGate.isGated("sms.app", ApplicationInfo.CATEGORY_SOCIAL, "sms.app"))
        assertFalse(
            AppGate.isGated("com.samsung.android.messaging", ApplicationInfo.CATEGORY_UNDEFINED, null)
        )
    }

    @Test
    fun `whatsapp business never waits, plain whatsapp still does`() {
        assertFalse(AppGate.isGated("com.whatsapp.w4b", ApplicationInfo.CATEGORY_SOCIAL, null))
        assertTrue(AppGate.isGated("com.whatsapp", ApplicationInfo.CATEGORY_UNDEFINED, null))
    }

    @Test
    fun `a pass survives launching and use, and ends on leaving`() {
        val fresh = GatePass(grantedAt = 1_000, used = false)
        // Still launching: our own wait screen is in front.
        val launching = GatePasses.passAfter(fresh, APP, WAKEPROOF, now = 2_000)
        assertEquals(fresh, launching)
        val inUse = GatePasses.passAfter(fresh, APP, APP, now = 3_000)
        assertEquals(GatePass(1_000, used = true), inUse)
        // Switching anywhere else, WakeProof included, ends the visit at once.
        assertNull(GatePasses.passAfter(inUse!!, APP, "com.other", now = 3_500))
        assertNull(GatePasses.passAfter(inUse, APP, WAKEPROOF, now = 3_500))
    }

    @Test
    fun `the shade or a file picker over the app does not end the visit`() {
        val inUse = GatePass(grantedAt = 1_000, used = true)
        assertEquals(inUse, GatePasses.passAfter(inUse, APP, "com.android.systemui", now = 9_000))
        assertEquals(inUse, GatePasses.passAfter(inUse, APP, "com.google.android.documentsui", now = 9_000))
    }

    @Test
    fun `a pass the app never used lapses after the launch window`() {
        val fresh = GatePass(grantedAt = 1_000, used = false)
        assertNull(
            GatePasses.passAfter(fresh, APP, WAKEPROOF, now = 1_001 + AppGate.PASS_LAUNCH_MILLIS)
        )
    }

    @Test
    fun `essential apps are never walled, even mid-session`() {
        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = true, focusSessionActive = true, onFocusList = false,
                capSpent = true, gated = true, hasPass = false
            )
        )
    }

    @Test
    fun `a focus session walls anything off the list, pass or not`() {
        assertEquals(
            GateVerdict.FOCUS_WALL,
            gateVerdict(
                essential = false, focusSessionActive = true, onFocusList = false,
                capSpent = false, gated = true, hasPass = true
            )
        )
        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = true, onFocusList = true,
                capSpent = false, gated = false, hasPass = false
            )
        )
    }

    @Test
    fun `a spent cap outranks the wait, and a pass opens a gated app`() {
        assertEquals(
            GateVerdict.CAP_WALL,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = true, gated = true, hasPass = true
            )
        )
        assertEquals(
            GateVerdict.WAIT,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, hasPass = false
            )
        )
        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, hasPass = true
            )
        )
    }

    private companion object {
        const val APP = "com.instagram.android"
        const val WAKEPROOF = "com.ogenrwotdaniel.wakeproof"
    }
}
