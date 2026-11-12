package com.ogenrwotdaniel.wakeproof.plan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure: how far a page zoomed to [zoom] may be dragged along one axis of a
 * viewer [size] long, either way, before its edge would leave the screen.
 */
fun maxPan(zoom: Float, size: Int): Float = ((zoom - 1f) * size / 2f).coerceAtLeast(0f)

/** Pure: the power-of-two subsampling that brings [width]×[height] down to about [maxSide]. */
fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/**
 * Decodes a photo straight from the camera, turned upright by its EXIF
 * orientation, for the "can you read it?" look before it is kept. Null when
 * it cannot be decoded.
 */
fun decodeUprightJpeg(jpeg: ByteArray, sampleSize: Int = 2): Bitmap? = try {
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.let { bitmap ->
        upright(bitmap, ExifInterface(java.io.ByteArrayInputStream(jpeg)).rotationDegrees)
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

private fun upright(bitmap: Bitmap, degrees: Int): Bitmap =
    if (degrees == 0) bitmap
    else Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees.toFloat()) }, true
    )

/**
 * Decodes a page photo no larger than about [maxSide], turned upright by its
 * EXIF orientation. Null for a file that is gone or is not an image; never a
 * throw, a missing thumbnail must not take the library down.
 */
suspend fun loadPageBitmap(file: File, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return@withContext null
        upright(bitmap, ExifInterface(file.path).rotationDegrees)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
