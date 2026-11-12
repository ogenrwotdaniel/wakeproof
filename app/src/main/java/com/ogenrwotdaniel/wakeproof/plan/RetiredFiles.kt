package com.ogenrwotdaniel.wakeproof.plan

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the removed day plan and evening review left in the app's private storage. */
internal fun retiredFiles(filesDir: File): List<File> = listOf(
    File(filesDir, "day_plans"),
    File(filesDir, "day_reviews.json"),
    File(filesDir, "day_reviews.json.part")
)

/**
 * Deletes the day-plan photos and the evening reviews (2026-11-13, the user's
 * request when both features were removed: nothing shows them any more, and
 * photos nobody can open or delete would only take up storage). Only those
 * three names, never the session summaries beside them. Run with the app on
 * screen, when the storage is unlocked; a failure is tried again next time.
 */
suspend fun deleteRetiredFiles(context: Context) = withContext(Dispatchers.IO) {
    // Once: a later feature that reused one of these names must not be wiped at every start.
    val prefs = context.getSharedPreferences("retired_files", Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_DONE, false)) return@withContext
    val gone = retiredFiles(context.filesDir).map { runCatching { it.deleteRecursively() }.getOrDefault(false) }
    if (gone.all { it }) prefs.edit().putBoolean(KEY_DONE, true).apply()
}

private const val KEY_DONE = "day_plans_and_reviews_deleted"
