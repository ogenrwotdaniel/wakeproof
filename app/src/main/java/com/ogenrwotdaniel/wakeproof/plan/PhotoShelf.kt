package com.ogenrwotdaniel.wakeproof.plan

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One kept photo of a handwritten page: a session's derivation/summary, optionally tagged with [subject]. */
data class PagePhoto(
    val file: File,
    val epochDay: Long,
    val takenAtMillis: Long,
    val subject: String? = null
)

/** Pure: newest day first, and within a day the latest photo first. */
fun sortedPages(photos: List<PagePhoto>): List<PagePhoto> =
    photos.sortedWith(compareByDescending<PagePhoto> { it.epochDay }.thenByDescending { it.takenAtMillis })

/** Days of pages a shelf keeps; older days' photos go when a new one is saved. */
const val PAGE_KEEP_DAYS = 90

/**
 * Pure: the photos to remove so that only the newest [keepDays] days that hold
 * a page remain. Counted in days that hold a photo, never against today's
 * date: a clock set a year ahead must not empty the library.
 */
fun pagesToPrune(photos: List<PagePhoto>, keepDays: Int = PAGE_KEEP_DAYS): List<PagePhoto> {
    val kept = photos.map { it.epochDay }.distinct().sortedDescending().take(keepDays).toSet()
    return photos.filter { it.epochDay !in kept }
}

/** Sanitizes an optional subject tag for safe embedding in a filename. */
private fun encodeSubjectSegment(subject: String?): String? {
    val cleaned = subject?.trim()?.replace(Regex("""[^A-Za-z0-9-]"""), "-")?.trim('-')?.take(32)
    return cleaned?.takeIf { it.isNotEmpty() }
}

/** The file name a kept photo goes under: what it is, its day, the moment it was taken, and optional subject. */
fun photoFileName(prefix: String, epochDay: Long, takenAtMillis: Long, subject: String? = null): String {
    val subj = encodeSubjectSegment(subject)
    return if (subj != null) {
        "${prefix}_${epochDay}_${takenAtMillis}_$subj.jpg"
    } else {
        "${prefix}_${epochDay}_$takenAtMillis.jpg"
    }
}

/** Pure: the day and moment in a [photoFileName] of [prefix], or null for any other file. */
fun parsePhotoFileName(prefix: String, name: String): Pair<Long, Long>? =
    parsePhotoFileMeta(prefix, name)?.let { (day, at, _) -> day to at }

/** Pure: the day, moment, and optional subject in a [photoFileName] of [prefix], or null for any other file. */
fun parsePhotoFileMeta(prefix: String, name: String): Triple<Long, Long, String?>? {
    if (!name.startsWith(prefix + "_")) return null
    val match = PHOTO_NAME.matchEntire(name.removePrefix(prefix + "_")) ?: return null
    val day = match.groupValues[1].toLongOrNull() ?: return null
    val at = match.groupValues[2].toLongOrNull() ?: return null
    val subject = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.replace('-', ' ')
    return Triple(day, at, subject)
}

// A day before 1970 (a clock set wrong) is negative and must still be listed.
private val PHOTO_NAME = Regex("""(-?\d+)_(\d+)(?:_([A-Za-z0-9-]+))?\.jpg""")

/**
 * One folder of photographed pages in the app's private storage: the session
 * summaries. (Until 2026-11-13 the day plans too; their old `day_plans`
 * folder is deleted by [deleteRetiredFiles].) Plain JPEG files named by [photoFileName], on this
 * device only; the newest [keepDays] days that hold a photo are kept.
 */
class PhotoShelf(
    private val dir: () -> File,
    private val prefix: String,
    private val keepDays: Int = PAGE_KEEP_DAYS
) {
    private val _revision = MutableStateFlow(0)

    /** Bumped on every save and delete, so a library reads again. */
    val revision: StateFlow<Int> = _revision

    /** Writes [jpeg] under [epochDay] (with optional [subject]). Throws when storage cannot be written. */
    suspend fun save(
        jpeg: ByteArray,
        epochDay: Long,
        now: Long,
        subject: String? = null
    ): PagePhoto = withContext(Dispatchers.IO) {
        val folder = dir()
        if (!folder.isDirectory && !folder.mkdirs()) error("photo folder unavailable")
        val target = File(folder, photoFileName(prefix, epochDay, now, subject))
        // Written beside it and renamed: a kill part-way must not leave half a
        // JPEG under a name the library would list.
        val partial = File(folder, target.name + ".part")
        try {
            partial.writeBytes(jpeg)
            if (!partial.renameTo(target)) error("photo could not be saved")
        } finally {
            partial.delete()
        }
        val parsedSubject = parsePhotoFileMeta(prefix, target.name)?.third
        val saved = PagePhoto(target, epochDay, now, parsedSubject)
        // Never the photo just taken, even under a day a wrong clock put far
        // back; and a failed clean-up must not fail the save.
        runCatching {
            pagesToPrune(listNow() + saved, keepDays).forEach { if (it.file != target) it.file.delete() }
        }
        _revision.update { it + 1 }
        saved
    }

    suspend fun list(): List<PagePhoto> = withContext(Dispatchers.IO) { sortedPages(listNow()) }

    private fun listNow(): List<PagePhoto> = dir().listFiles().orEmpty().mapNotNull { file ->
        parsePhotoFileMeta(prefix, file.name)?.let { (day, at, subject) ->
            PagePhoto(file, day, at, subject)
        }
    }

    suspend fun delete(photo: PagePhoto) = withContext(Dispatchers.IO) {
        // Only a file of this shelf, whatever path the caller holds.
        if (photo.file.parentFile == dir() && parsePhotoFileName(prefix, photo.file.name) != null) {
            photo.file.delete()
        }
        _revision.update { it + 1 }
    }
}
