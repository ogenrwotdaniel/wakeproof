package com.ogenrwotdaniel.wakeproof.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Shrinks captured photos before upload (interface so unit tests can bypass). */
interface ImageCompressor {
    fun compressToJpeg(imageBytes: ByteArray, maxDimension: Int, quality: Int): ByteArray
}

/** Bitmap-based implementation: scale longest side to [maxDimension], re-encode JPEG. */
class AndroidImageCompressor @Inject constructor() : ImageCompressor {
    override fun compressToJpeg(imageBytes: ByteArray, maxDimension: Int, quality: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return imageBytes
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > maxDimension) {
            val scale = maxDimension.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
