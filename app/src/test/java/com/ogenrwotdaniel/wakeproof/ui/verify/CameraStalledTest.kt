package com.ogenrwotdaniel.wakeproof.ui.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStalledTest {

    private val limit = NO_FRAME_MS
    private val start = 1_000_000L

    @Test
    fun `a camera that never delivers is stalled once the limit passes`() {
        assertFalse(cameraStalled(start + limit, watchingSince = start, lastFrameAt = 0L))
        assertTrue(cameraStalled(start + limit + 1, watchingSince = start, lastFrameAt = 0L))
    }

    @Test
    fun `a camera that stops delivering later is stalled too`() {
        val frame = start + 5 * limit
        assertFalse(cameraStalled(frame + limit, start, frame))
        assertTrue(cameraStalled(frame + limit + 1, start, frame))
    }

    @Test
    fun `an unwatched camera is never stalled, so a stopped screen reads as nothing`() {
        assertFalse(cameraStalled(start + 100 * limit, watchingSince = null, lastFrameAt = 0L))
    }

    @Test
    fun `a locked alarm that sees nobody for ten minutes gets its fallback`() {
        assertFalse(strictCameraBlind(start + STRICT_NO_POSE_MS, start))
        assertTrue(strictCameraBlind(start + STRICT_NO_POSE_MS + 1, start))
    }

    @Test
    fun `watching again starts the wait afresh`() {
        val resumed = start + 50 * limit
        assertFalse(cameraStalled(resumed + 1, watchingSince = resumed, lastFrameAt = start))
    }
}
