package com.ogenrwotdaniel.wakeproof.verify.water

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a captured JPEG into the fixed-scale, upright [RgbImage] the detector
 * expects. An interface so the detector's tests never touch Android.
 */
interface WaterImageSampler {
    /** Null when the bytes are not a decodable image. */
    fun sample(bytes: ByteArray): RgbImage?
}

/**
 * Decode → rotate upright → resample to [WaterLineDetector.ANALYSIS_HEIGHT].
 *
 * Both steps are load-bearing, for reasons that are easy to skip:
 *
 * **Orientation.** The whole method rests on a water line being horizontal in
 * the pixel grid, because gravity makes it so. A sideways photograph makes it
 * vertical and every cue collapses. CameraX's `OnImageCapturedCallback` hands
 * back the sensor's JPEG with the rotation recorded in EXIF and *not* applied to
 * the pixels, and `BitmapFactory` ignores EXIF, so without this the detector
 * would be handed a rotated bottle whenever the phone was.
 *
 * **Scale.** [WaterLineDetector]'s smoothing radii and band spans are counted in
 * rows and were fitted at this height. Handing it a 3000-row photograph would
 * quietly make the gradient smoothing a tenth of what it is meant to be.
 */
class AndroidWaterImageSampler @Inject constructor() : WaterImageSampler {

    override fun sample(bytes: ByteArray): RgbImage? {
        val decoded = decodeDownscaled(bytes) ?: return null
        val upright = applyExifRotation(decoded, bytes)
        val scaled = resampleToAnalysisHeight(upright)

        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== upright) scaled.recycle()
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return RgbImage(scaled.width, scaled.height, pixels)
    }

    /**
     * Decodes at roughly the size wanted rather than full resolution. A 12 MP
     * capture is ~48 MB as a bitmap and the analysis throws all but a few hundred
     * rows of it away, so paying for the full decode is pure cost — and on the
     * critical path of an alarm.
     */
    private fun decodeDownscaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0 || bounds.outWidth <= 0) return null

        // The shorter side is used because EXIF may still rotate the image, and
        // over-decoding is recoverable where under-decoding is not.
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortest / (sample * 2) >= WaterLineDetector.ANALYSIS_HEIGHT) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Unreadable or absent EXIF leaves the bitmap alone, which is the common case. */
    private fun applyExifRotation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val degrees = runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun resampleToAnalysisHeight(bitmap: Bitmap): Bitmap {
        val target = WaterLineDetector.ANALYSIS_HEIGHT
        if (bitmap.height == target) return bitmap
        val scale = target.toDouble() / bitmap.height
        val width = max(2, (bitmap.width * scale).roundToInt())
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, width, target, true)
        }.getOrDefault(bitmap)
    }
}
