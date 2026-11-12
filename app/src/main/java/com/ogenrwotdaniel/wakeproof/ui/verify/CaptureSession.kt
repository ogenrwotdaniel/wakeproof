package com.ogenrwotdaniel.wakeproof.ui.verify

import kotlinx.coroutines.Job

/** How a photo attempt ended without a picture, as far as fault counting cares. */
enum class CaptureFault {
    /** The sensor or pipeline failed the capture. */
    FAILED,
    /** CameraX could not say why. */
    UNKNOWN,
    /** The camera was closed while the photo was pending. */
    CLOSED,
    /** Anything else, such as a tap before the camera was bound. */
    OTHER
}

/**
 * One photo at a time on a photo screen (the day plan), and when a run of failed photos means
 * the camera is broken (which opens the emergency dismiss).
 *
 * A camera closed by a flip, a rebind or the screen leaving the foreground is
 * not broken. Those count only if none of them happened at any point while the
 * photo was pending, not just at the moment the answer arrived: the screen can
 * go off and come back before CameraX reports the close.
 *
 * Main thread only: the button, the CameraX callbacks on the main executor and
 * the watchdog coroutine all run there.
 */
class CaptureSession(private val maxErrors: Int = MAX_CAPTURE_ERRORS) {
    private var shot = 0
    private var busy = false
    private var errors = 0
    private var binds = 0
    private var bindAtTap = 0
    private var interrupted = false
    private var disposed = false
    /** The pending photo's timeout; every way a photo ends cancels it here. */
    private var watchdog: Job? = null

    /** Hands over the timeout for the photo [begin] just started. */
    fun arm(timeout: Job) {
        watchdog?.cancel()
        watchdog = timeout
    }

    private fun disarm() {
        watchdog?.cancel()
        watchdog = null
    }

    /** Starts a photo; null while one is pending (a double tap) or after disposal. */
    fun begin(): Int? {
        if (busy || disposed) return null
        busy = true
        interrupted = false
        bindAtTap = binds
        return ++shot
    }

    /** The camera was bound again (first bind, flip or retry). */
    fun bound() {
        binds++
    }

    /** The screen paused (switched away or screen off); a pending photo may be aborted. */
    fun leftForeground() {
        if (busy) interrupted = true
    }

    fun dispose() {
        disposed = true
        disarm()
    }

    /** True when [id] is the current photo: use it. A stale one must be discarded. */
    fun succeeded(id: Int): Boolean {
        if (id != shot || !busy) return false
        busy = false
        disarm()
        // A frame arriving after the screen left is for a step that is gone.
        if (disposed) return false
        errors = 0
        return true
    }

    /** Ends [id] without a picture; true when the camera should now be called broken. */
    fun failed(id: Int, fault: CaptureFault): Boolean {
        if (id != shot || !busy) return false
        busy = false
        disarm()
        // A flip, a rebind or the screen leaving can end a pending photo with any
        // error, not only CLOSED: none of them is the camera's fault.
        val excused = interrupted || binds != bindAtTap
        val counts = !excused && fault != CaptureFault.OTHER
        return !disposed && counts && ++errors >= maxErrors
    }

    /**
     * [id] got no answer in time. The button is freed either way. It counts like
     * a failed photo, one strike of [maxErrors], so a single slow capture never
     * opens the emergency dismiss; and not at all when a flip, a rebind or the
     * screen leaving dropped the request, since those can drop it unanswered.
     */
    fun timedOut(id: Int): Boolean {
        if (id != shot || !busy) return false
        busy = false
        watchdog = null // called from the timeout itself
        shot++ // a late answer to this photo is now stale
        val counts = !interrupted && binds == bindAtTap
        return !disposed && counts && ++errors >= maxErrors
    }

    /** "Try the camera again": a clean slate, and any pending answer goes stale. */
    fun reset() {
        busy = false
        disarm()
        errors = 0
        shot++
    }

    companion object {
        const val MAX_CAPTURE_ERRORS = 2

        /** A photo with no answer by now never gets one: the camera has stalled. */
        const val CAPTURE_TIMEOUT_MS = 10_000L
    }
}
