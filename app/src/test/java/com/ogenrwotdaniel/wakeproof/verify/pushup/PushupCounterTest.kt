package com.ogenrwotdaniel.wakeproof.verify.pushup

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class PushupCounterTest {

    /**
     * Builds a pose with a given elbow angle. Shoulder at origin, elbow below
     * it; the wrist is rotated so the shoulder–elbow–wrist angle equals
     * [elbowAngleDeg]. Torso is horizontal unless [horizontal] is false.
     */
    private fun pose(elbowAngleDeg: Float, horizontal: Boolean = true): PushupPose {
        val shoulder = PosePoint(0f, 0f)
        val elbow = PosePoint(0f, 50f)
        val rad = Math.toRadians(elbowAngleDeg.toDouble())
        val wrist = PosePoint(
            elbow.x + (50 * sin(rad)).toFloat(),
            elbow.y + (-50 * cos(rad)).toFloat()
        )
        val hip = if (horizontal) PosePoint(100f, 0f) else PosePoint(0f, 100f)
        return PushupPose(
            leftShoulder = shoulder, leftElbow = elbow, leftWrist = wrist,
            rightShoulder = shoulder, rightElbow = elbow, rightWrist = wrist,
            leftHip = hip, rightHip = hip
        )
    }

    @Test
    fun `full reps count`() {
        val counter = PushupCounter()
        repeat(15) {
            counter.process(pose(170f))
            counter.process(pose(60f))
            counter.process(pose(170f))
        }
        assertEquals(15, counter.reps)
    }

    @Test
    fun `half reps do not count`() {
        val counter = PushupCounter()
        repeat(10) {
            counter.process(pose(170f))
            counter.process(pose(120f)) // never below the 90° DOWN threshold
        }
        assertEquals(0, counter.reps)
    }

    @Test
    fun `standing arm bends do not count`() {
        val counter = PushupCounter()
        repeat(10) {
            val state = counter.process(pose(170f, horizontal = false))
            assertEquals(PushupCounter.Status.NOT_HORIZONTAL, state.status)
            counter.process(pose(60f, horizontal = false))
            counter.process(pose(170f, horizontal = false))
        }
        assertEquals(0, counter.reps)
    }

    @Test
    fun `posture break mid-rep cancels the rep`() {
        val counter = PushupCounter()
        counter.process(pose(60f)) // down, in position
        counter.process(pose(120f, horizontal = false)) // stood up mid-rep
        counter.process(pose(170f)) // back up, horizontal again
        assertEquals(0, counter.reps)
    }

    @Test
    fun `hysteresis between thresholds neither counts nor resets`() {
        val counter = PushupCounter()
        counter.process(pose(170f))
        counter.process(pose(60f))
        counter.process(pose(120f)) // between thresholds
        counter.process(pose(170f)) // completes the rep
        assertEquals(1, counter.reps)
    }

    @Test
    fun `no pose reports status and keeps count`() {
        val counter = PushupCounter()
        counter.process(pose(170f))
        counter.process(pose(60f))
        counter.process(pose(170f))
        val state = counter.process(null)
        assertEquals(PushupCounter.Status.NO_POSE, state.status)
        assertEquals(1, state.reps)
    }
}
