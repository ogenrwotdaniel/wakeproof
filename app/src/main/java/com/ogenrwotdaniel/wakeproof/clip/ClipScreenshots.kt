package com.ogenrwotdaniel.wakeproof.clip

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log

/**
 * Watches MediaStore for new screenshots (rows whose RELATIVE_PATH contains
 * "Screenshots") and hands each one to [onScreenshot] once.
 *
 * Needs READ_MEDIA_IMAGES, which only the debug (sideloaded, device-owner)
 * build declares — Play's photo-permission policy would not accept it for this.
 * Without the grant [start] does nothing.
 */
class ClipScreenshots(
    private val context: Context,
    private val onScreenshot: (uri: Uri, mime: String) -> Unit
) {
    private var observer: ContentObserver? = null
    private val handled = LinkedHashSet<Long>()
    private var startedAtSeconds = 0L

    fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (observer != null || !granted()) return
        startedAtSeconds = System.currentTimeMillis() / 1000
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = scan()
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o
            )
            observer = o
        }.onFailure { Log.w(TAG, "observer", it) }
    }

    fun stop() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    /** Pending rows are invisible to a plain query, so a screenshot shows up once it is finished. */
    private fun scan() {
        if (!granted()) return
        val since = maxOf(startedAtSeconds, System.currentTimeMillis() / 1000 - RECENT_SECONDS)
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf("%Screenshots%", since.toString()),
                "${MediaStore.Images.Media._ID} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (!handled.add(id)) continue
                    while (handled.size > 200) handled.remove(handled.first())
                    val mime = c.getString(1) ?: ClipCodec.MIME_PNG
                    onScreenshot(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), mime)
                }
            }
        }.onFailure { Log.w(TAG, "scan", it) }
    }

    private companion object {
        const val TAG = "ClipScreenshots"
        const val RECENT_SECONDS = 60L
    }
}
