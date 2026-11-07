package com.ogenrwotdaniel.wakeproof.verify.pushup

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Landmarks of one analyzed frame: the [PushupPose] for counting plus raw
 * points and the (rotation-corrected) image size for the skeleton overlay.
 */
data class PoseFrame(
    val pose: PushupPose?,
    val skeletonPoints: List<PosePoint>,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * CameraX analyzer running ML Kit Pose Detection in STREAM_MODE (on-device,
 * no network) and mapping results to pure-Kotlin [PoseFrame]s.
 */
class PoseAnalyzer(private val onFrame: (PoseFrame) -> Unit) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotated = rotation == 90 || rotation == 270
        val width = if (rotated) imageProxy.height else imageProxy.width
        val height = if (rotated) imageProxy.width else imageProxy.height

        // A bad frame costs that frame. Thrown from here it would kill the process,
        // and a locked alarm would come straight back to the same crash; if every
        // frame fails, no frame arrives and the verify screen's stall check takes over.
        try {
            detector.process(InputImage.fromMediaImage(mediaImage, rotation))
                .addOnSuccessListener { pose ->
                    runCatching { onFrame(pose.toPoseFrame(width, height)) }
                        .onFailure { Log.w(TAG, "frame dropped", it) }
                }
                .addOnFailureListener { Log.w(TAG, "detection failed", it) }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            Log.w(TAG, "frame dropped", e)
            imageProxy.close()
        }
    }

    fun shutdown() = detector.close()

    private companion object {
        const val TAG = "PoseAnalyzer"
    }

    private fun Pose.toPoseFrame(width: Int, height: Int): PoseFrame {
        fun point(type: Int): PosePoint? =
            getPoseLandmark(type)?.let { PosePoint(it.position.x, it.position.y) }

        val pushupPose = if (allPoseLandmarks.isEmpty()) {
            null
        } else {
            PushupPose(
                leftShoulder = point(PoseLandmark.LEFT_SHOULDER),
                leftElbow = point(PoseLandmark.LEFT_ELBOW),
                leftWrist = point(PoseLandmark.LEFT_WRIST),
                rightShoulder = point(PoseLandmark.RIGHT_SHOULDER),
                rightElbow = point(PoseLandmark.RIGHT_ELBOW),
                rightWrist = point(PoseLandmark.RIGHT_WRIST),
                leftHip = point(PoseLandmark.LEFT_HIP),
                rightHip = point(PoseLandmark.RIGHT_HIP)
            )
        }
        return PoseFrame(
            pose = pushupPose,
            skeletonPoints = allPoseLandmarks.map { PosePoint(it.position.x, it.position.y) },
            imageWidth = width,
            imageHeight = height
        )
    }
}
