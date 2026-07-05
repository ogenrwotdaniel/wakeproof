package com.ogenrwotdaniel.wakeproof.verify.pushup

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure-Kotlin pushup rep counter. Feed it one [PushupPose] per camera frame.
 *
 * State machine on the elbow angle (shoulder–elbow–wrist, averaged over the
 * visible arms): UP above [UP_ANGLE_DEG], DOWN below [DOWN_ANGLE_DEG],
 * hysteresis in between. A DOWN→UP transition counts one rep. Counting pauses
 * unless the torso (shoulder→hip line) is within [HORIZONTAL_TOLERANCE_DEG] of
 * horizontal, so standing arm-bends never count.
 */
class PushupCounter {

    enum class Status { NO_POSE, NOT_HORIZONTAL, UP, DOWN, BETWEEN }

    data class State(val reps: Int, val status: Status)

    var reps: Int = 0
        private set

    private var wasDown = false

    fun process(pose: PushupPose?): State {
        val elbowAngle = pose?.let(::averageElbowAngle)
            ?: return State(reps, Status.NO_POSE).also { wasDown = false }

        if (!isHorizontal(pose)) {
            // Posture broke: require going back DOWN before the next rep counts.
            wasDown = false
            return State(reps, Status.NOT_HORIZONTAL)
        }

        return when {
            elbowAngle < DOWN_ANGLE_DEG -> {
                wasDown = true
                State(reps, Status.DOWN)
            }
            elbowAngle > UP_ANGLE_DEG -> {
                if (wasDown) {
                    reps++
                    wasDown = false
                }
                State(reps, Status.UP)
            }
            else -> State(reps, Status.BETWEEN)
        }
    }

    fun reset() {
        reps = 0
        wasDown = false
    }

    /** Average elbow angle over arms with all three landmarks visible; null if neither. */
    private fun averageElbowAngle(pose: PushupPose): Float? {
        val angles = listOfNotNull(
            angleAt(pose.leftShoulder, pose.leftElbow, pose.leftWrist),
            angleAt(pose.rightShoulder, pose.rightElbow, pose.rightWrist)
        )
        return if (angles.isEmpty()) null else angles.sum() / angles.size
    }

    private fun angleAt(a: PosePoint?, vertex: PosePoint?, c: PosePoint?): Float? {
        if (a == null || vertex == null || c == null) return null
        val v1x = a.x - vertex.x
        val v1y = a.y - vertex.y
        val v2x = c.x - vertex.x
        val v2y = c.y - vertex.y
        val len1 = hypot(v1x, v1y)
        val len2 = hypot(v2x, v2y)
        if (len1 == 0f || len2 == 0f) return null
        val cross = v1x * v2y - v1y * v2x
        val dot = v1x * v2x + v1y * v2y
        return Math.toDegrees(abs(atan2(cross, dot)).toDouble()).toFloat()
    }

    /** True when at least one shoulder→hip line is close to horizontal. */
    private fun isHorizontal(pose: PushupPose): Boolean {
        val torsoAngles = listOfNotNull(
            lineAngleDeg(pose.leftShoulder, pose.leftHip),
            lineAngleDeg(pose.rightShoulder, pose.rightHip)
        )
        if (torsoAngles.isEmpty()) return false
        return torsoAngles.any { angle ->
            val fromHorizontal = minOf(angle, 180f - angle)
            fromHorizontal <= HORIZONTAL_TOLERANCE_DEG
        }
    }

    /** Absolute line angle in [0, 180) degrees, 0 = horizontal. */
    private fun lineAngleDeg(a: PosePoint?, b: PosePoint?): Float? {
        if (a == null || b == null) return null
        val deg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
        return (((deg % 180) + 180) % 180).toFloat()
    }

    companion object {
        // The two numbers that matter for miscounts — tune on-device if needed.
        const val UP_ANGLE_DEG = 155f
        const val DOWN_ANGLE_DEG = 90f
        const val HORIZONTAL_TOLERANCE_DEG = 30f
    }
}
