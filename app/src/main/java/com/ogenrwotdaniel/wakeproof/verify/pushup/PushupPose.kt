package com.ogenrwotdaniel.wakeproof.verify.pushup

/** A 2D landmark position in image coordinates. */
data class PosePoint(val x: Float, val y: Float)

/**
 * The landmarks [PushupCounter] needs, decoupled from ML Kit types so the
 * counter stays pure Kotlin and unit-testable. Nullable = not detected.
 */
data class PushupPose(
    val leftShoulder: PosePoint? = null,
    val leftElbow: PosePoint? = null,
    val leftWrist: PosePoint? = null,
    val rightShoulder: PosePoint? = null,
    val rightElbow: PosePoint? = null,
    val rightWrist: PosePoint? = null,
    val leftHip: PosePoint? = null,
    val rightHip: PosePoint? = null
)
