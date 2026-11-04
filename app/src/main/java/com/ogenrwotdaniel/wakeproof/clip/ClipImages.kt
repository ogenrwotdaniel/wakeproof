package com.ogenrwotdaniel.wakeproof.clip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The Bitmap side of clip images, kept out of [ClipCodec] so the codec stays
 * JVM-testable: JPEG re-encoding for the cloud, converting formats the protocol
 * does not carry (WebP, HEIC, GIF) to PNG, and notification thumbnails.
 */
object ClipImages {

    /** Re-encodes [bytes] as JPEG q90 (the cloud path for images over 2 MiB); null if undecodable. */
    fun toJpeg(bytes: ByteArray, quality: Int = ClipCodec.CLOUD_JPEG_QUALITY): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [bytes] as a mime the protocol carries: PNG/JPEG pass through untouched,
     * anything else decodable becomes PNG. Null if it is not an image at all.
     */
    fun normalise(mime: String?, bytes: ByteArray): Pair<String, ByteArray>? {
        when (mime) {
            ClipCodec.MIME_PNG, ClipCodec.MIME_JPEG -> return mime to bytes
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            ClipCodec.MIME_PNG to ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** A notification-sized decode of [file] (longest side ≈ [maxSide] px). */
    fun thumbnail(file: File, maxSide: Int = 720): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Reads at most [limit] + 1 bytes, so a caller can tell "too big" without reading it all. */
    fun readCapped(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total <= limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
