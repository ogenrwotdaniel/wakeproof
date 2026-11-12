package com.ogenrwotdaniel.wakeproof.plan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One saved photo of a day's handwritten plan. */
data class DayPlanPhoto(val file: File, val epochDay: Long, val takenAtMillis: Long)

/** The file name a plan photo is kept under: its day, then the moment it was taken. */
fun dayPlanFileName(epochDay: Long, takenAtMillis: Long): String =
    "plan_${epochDay}_$takenAtMillis.jpg"

/** Pure: the day and moment in a [dayPlanFileName], or null for any other file. */
fun parseDayPlanFileName(name: String): Pair<Long, Long>? {
    val match = DAY_PLAN_NAME.matchEntire(name) ?: return null
    val day = match.groupValues[1].toLongOrNull() ?: return null
    val at = match.groupValues[2].toLongOrNull() ?: return null
    return day to at
}

// A day before 1970 (a clock set wrong) is negative and must still be listed.
private val DAY_PLAN_NAME = Regex("""plan_(-?\d+)_(\d+)\.jpg""")

/** Pure: newest day first, and within a day the latest photo first. */
fun sortedDayPlans(photos: List<DayPlanPhoto>): List<DayPlanPhoto> =
    photos.sortedWith(compareByDescending<DayPlanPhoto> { it.epochDay }.thenByDescending { it.takenAtMillis })

/** Days of plans the library keeps; older days' photos go when a new one is saved. */
const val DAY_PLAN_KEEP_DAYS = 90

/**
 * Pure: the photos to remove so that only the newest [keepDays] days that have
 * a plan remain. Counted in days that hold a photo, never against today's
 * date: a clock set a year ahead must not empty the library.
 */
fun dayPlansToPrune(photos: List<DayPlanPhoto>, keepDays: Int = DAY_PLAN_KEEP_DAYS): List<DayPlanPhoto> {
    val kept = photos.map { it.epochDay }.distinct().sortedDescending().take(keepDays).toSet()
    return photos.filter { it.epochDay !in kept }
}

/**
 * The library of day-plan photos (2026-11-12, the user's request: an 08:20
 * alarm to write down the day and photograph the page, kept to look back on).
 *
 * Plain JPEG files in the app's private storage, one per photo, on this device
 * only: nothing is uploaded, synced or shared, and uninstalling removes them.
 * The newest [DAY_PLAN_KEEP_DAYS] days of plans are kept, so the library
 * cannot grow without end.
 * Not readable before the first unlock after a restart, so a save can fail
 * then; the alarm screen has its own way out for that.
 */
@Singleton
class DayPlanStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File get() = File(context.filesDir, DIR)

    private val _revision = MutableStateFlow(0)

    /** Bumped on every save and delete, so the library reads again. */
    val revision: StateFlow<Int> = _revision

    /** Writes [jpeg] as a plan for [epochDay]. Throws when storage cannot be written. */
    suspend fun save(
        jpeg: ByteArray,
        epochDay: Long = LocalDate.now().toEpochDay(),
        now: Long = System.currentTimeMillis()
    ): DayPlanPhoto = withContext(Dispatchers.IO) {
        val folder = dir
        if (!folder.isDirectory && !folder.mkdirs()) error("day plan folder unavailable")
        val target = File(folder, dayPlanFileName(epochDay, now))
        // Written beside it and renamed: a kill part-way must not leave half a
        // JPEG under a name the library would list.
        val partial = File(folder, target.name + ".part")
        try {
            partial.writeBytes(jpeg)
            if (!partial.renameTo(target)) error("day plan could not be saved")
        } finally {
            partial.delete()
        }
        val saved = DayPlanPhoto(target, epochDay, now)
        // Never the photo just taken, even under a day a wrong clock put far
        // back; and a failed clean-up must not fail the save.
        runCatching {
            dayPlansToPrune(listNow() + saved).forEach { if (it.file != target) it.file.delete() }
        }
        _revision.update { it + 1 }
        saved
    }

    suspend fun list(): List<DayPlanPhoto> = withContext(Dispatchers.IO) { sortedDayPlans(listNow()) }

    private fun listNow(): List<DayPlanPhoto> = dir.listFiles().orEmpty().mapNotNull { file ->
        parseDayPlanFileName(file.name)?.let { (day, at) -> DayPlanPhoto(file, day, at) }
    }

    suspend fun delete(photo: DayPlanPhoto) = withContext(Dispatchers.IO) {
        // Only a file of this library, whatever path the caller holds.
        if (photo.file.parentFile == dir && parseDayPlanFileName(photo.file.name) != null) {
            photo.file.delete()
        }
        _revision.update { it + 1 }
    }

    private companion object {
        const val DIR = "day_plans"
    }
}

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
 * Decodes a plan photo no larger than about [maxSide], turned upright by its
 * EXIF orientation. Null for a file that is gone or is not an image; never a
 * throw, a missing thumbnail must not take the library down.
 */
suspend fun loadDayPlanBitmap(file: File, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
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
