package com.ogenrwotdaniel.wakeproof.ui.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Job
import org.junit.Test

class CaptureSessionTest {

    private fun boundSession() = CaptureSession().also { it.bound() }

    @Test
    fun `a double tap takes one photo`() {
        val s = boundSession()
        assertNotNull(s.begin())
        assertNull(s.begin())
    }

    @Test
    fun `two failed captures in a row mean a broken camera`() {
        val s = boundSession()
        assertFalse(s.failed(s.begin()!!, CaptureFault.FAILED))
        assertTrue(s.failed(s.begin()!!, CaptureFault.UNKNOWN))
    }

    @Test
    fun `a good photo clears the error run`() {
        val s = boundSession()
        assertFalse(s.failed(s.begin()!!, CaptureFault.FAILED))
        assertTrue(s.succeeded(s.begin()!!))
        assertFalse(s.failed(s.begin()!!, CaptureFault.FAILED))
    }

    @Test
    fun `a camera that closes on every photo is broken`() {
        val s = boundSession()
        assertFalse(s.failed(s.begin()!!, CaptureFault.CLOSED))
        assertTrue(s.failed(s.begin()!!, CaptureFault.CLOSED))
    }

    @Test
    fun `a close from a flip is not a fault`() {
        val s = boundSession()
        repeat(3) {
            val id = s.begin()!!
            s.bound()
            assertFalse(s.failed(id, CaptureFault.CLOSED))
        }
    }

    @Test
    fun `any error from a flip or the screen leaving is not a fault`() {
        val s = boundSession()
        repeat(3) {
            val flipped = s.begin()!!
            s.bound()
            assertFalse(s.failed(flipped, CaptureFault.FAILED))
            val paused = s.begin()!!
            s.leftForeground()
            assertFalse(s.failed(paused, CaptureFault.UNKNOWN))
        }
    }

    @Test
    fun `a close after the screen went off and back is not a fault`() {
        val s = boundSession()
        repeat(3) {
            val id = s.begin()!!
            s.leftForeground() // the answer arrives once the screen is back on
            assertFalse(s.failed(id, CaptureFault.CLOSED))
        }
    }

    @Test
    fun `a pause before the tap does not excuse a later close`() {
        val s = boundSession()
        s.leftForeground()
        assertFalse(s.failed(s.begin()!!, CaptureFault.CLOSED))
        assertTrue(s.failed(s.begin()!!, CaptureFault.CLOSED))
    }

    @Test
    fun `one slow photo is a strike, two stalled photos a broken camera`() {
        val s = boundSession()
        assertFalse(s.timedOut(s.begin()!!))
        assertTrue(s.timedOut(s.begin()!!))
    }

    @Test
    fun `a stalled photo frees the button`() {
        val s = boundSession()
        s.timedOut(s.begin()!!)
        assertNotNull(s.begin())
    }

    @Test
    fun `a photo dropped by a flip is not a strike`() {
        val s = boundSession()
        repeat(3) {
            val id = s.begin()!!
            s.bound()
            assertFalse(s.timedOut(id))
        }
    }

    @Test
    fun `a frame arriving after the screen left is not used`() {
        val s = boundSession()
        val id = s.begin()!!
        s.dispose()
        assertFalse(s.succeeded(id))
    }

    @Test
    fun `a photo stalled by leaving the screen frees the button without a fault`() {
        val s = boundSession()
        val id = s.begin()!!
        s.leftForeground()
        assertFalse(s.timedOut(id))
        assertNotNull(s.begin())
    }

    @Test
    fun `a late answer to a timed-out photo is discarded`() {
        val s = boundSession()
        val id = s.begin()!!
        s.timedOut(id)
        assertFalse(s.succeeded(id))
        assertFalse(s.failed(id, CaptureFault.FAILED))
    }

    @Test
    fun `retry forgets the errors and the pending photo`() {
        val s = boundSession()
        assertFalse(s.failed(s.begin()!!, CaptureFault.FAILED))
        val pending = s.begin()!!
        s.reset()
        assertFalse(s.succeeded(pending))
        assertFalse(s.failed(s.begin()!!, CaptureFault.FAILED))
    }

    @Test
    fun `nothing counts once the screen is gone`() {
        val s = boundSession()
        assertFalse(s.failed(s.begin()!!, CaptureFault.FAILED))
        val id = s.begin()!!
        s.dispose()
        assertFalse(s.failed(id, CaptureFault.FAILED))
        assertNull(s.begin())
    }

    @Test
    fun `an answered photo cancels its timeout`() {
        val s = boundSession()
        val id = s.begin()!!
        val timeout = Job().also(s::arm)
        assertTrue(s.succeeded(id))
        assertTrue(timeout.isCancelled)
    }

    @Test
    fun `a stale answer leaves the newer photo's timeout running`() {
        val s = boundSession()
        val old = s.begin()!!
        s.arm(Job())
        s.reset()
        s.begin()!!
        val timeout = Job().also(s::arm)
        assertFalse(s.failed(old, CaptureFault.FAILED))
        assertFalse(timeout.isCancelled)
    }

    @Test
    fun `timeout constant is ten seconds`() {
        assertEquals(10_000L, CaptureSession.CAPTURE_TIMEOUT_MS)
    }
}
