package com.ogenrwotdaniel.wakeproof.clip

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.ogenrwotdaniel.wakeproof.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Puts a received clip where it is useful: on the clipboard (text as plain text,
 * an image as a FileProvider URI) and, for images, a quiet notification with the
 * picture and "Open in Notes". If writing the clipboard from the background fails,
 * the notification is the fallback — tapping it opens [ClipApplyActivity], which
 * sets the clip from the foreground.
 */
class ClipDelivery(private val context: Context) {

    private val clipboard by lazy { context.getSystemService(ClipboardManager::class.java) }
    private val notifications by lazy { context.getSystemService(NotificationManager::class.java) }

    /** Delivers [clip]; returns whether it reached the clipboard. */
    suspend fun deliver(clip: OpenedClip): Boolean {
        val h = clip.header
        val file = withContext(Dispatchers.IO) { store(h, clip.body) }
        val clipped = withContext(Dispatchers.Main) { setClipboard(context, h.mime, file) }
        if (h.mime != ClipCodec.MIME_TEXT) {
            notifyImage(h, file, clipped)
        } else if (!clipped) {
            notifyText(h, file)
        }
        return clipped
    }

    private fun store(h: ClipHeader, body: ByteArray): File {
        val dir = cacheDir(context)
        trim(dir)
        val ext = when (h.mime) {
            ClipCodec.MIME_PNG -> "png"
            ClipCodec.MIME_JPEG -> "jpg"
            else -> "txt"
        }
        return File(dir, "${h.createdAt}-${h.id.take(8)}.$ext").apply { writeBytes(body) }
    }

    /** Keeps the newest [KEEP_FILES] files and nothing older than a day. */
    private fun trim(dir: File) {
        val files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        files.forEachIndexed { i, f -> if (i >= KEEP_FILES || f.lastModified() < cutoff) f.delete() }
    }

    private fun notifyImage(h: ClipHeader, file: File, clipped: Boolean) {
        if (!canNotify()) return
        ensureChannel(context)
        val uri = uriFor(context, file)
        val title = "Image from ${h.name} — " + if (clipped) "paste anywhere" else "tap to copy"
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (clipped) "It's on your clipboard." else "Tap to put it on the clipboard.")
            .setAutoCancel(true)
            .setContentIntent(applyIntent(file, h))
            .addAction(
                Notification.Action.Builder(null, "Open in Notes", openInNotes(uri, h.mime)).build()
            )
        ClipImages.thumbnail(file)?.let { bmp ->
            builder.setLargeIcon(bmp).setStyle(Notification.BigPictureStyle().bigPicture(bmp))
        }
        runCatching { notifications.notify(NOTIFICATION_TAG, notificationId(h), builder.build()) }
            .onFailure { Log.w(TAG, "notify", it) }
    }

    private fun notifyText(h: ClipHeader, file: File) {
        if (!canNotify()) return
        ensureChannel(context)
        val preview = runCatching { file.readText().take(200) }.getOrDefault("")
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Text from ${h.name} — tap to copy")
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setContentIntent(applyIntent(file, h))
            .build()
        runCatching { notifications.notify(NOTIFICATION_TAG, notificationId(h), n) }
    }

    private fun canNotify() =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(h: ClipHeader) = h.id.hashCode()

    private fun applyIntent(file: File, h: ClipHeader): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(h),
        ClipApplyActivity.intent(context, file.name, h.mime),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** ACTION_SEND straight to Samsung Notes; a chooser when Notes is not installed. */
    private fun openInNotes(uri: Uri, mime: String): PendingIntent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val toNotes = Intent(send).setPackage(NOTES_PACKAGE)
        val target = if (context.packageManager.resolveActivity(toNotes, 0) != null) {
            toNotes
        } else {
            Intent.createChooser(send, "Open with").addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        return PendingIntent.getActivity(
            context, uri.hashCode(), target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val TAG = "ClipDelivery"
        const val CHANNEL_ID = "wakeproof_clip"
        const val NOTES_PACKAGE = "com.samsung.android.app.notes"
        private const val NOTIFICATION_TAG = "clip"
        private const val KEEP_FILES = 20
        const val CLIP_LABEL = "WakeProof Clip"

        fun cacheDir(context: Context): File = File(context.cacheDir, "clip").apply { mkdirs() }

        fun uriFor(context: Context, file: File): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.clipfiles", file)

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Clipboard from your devices", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Images and texts copied on your other devices" }
            )
        }

        /** Sets [file] (a stored clip) as the primary clip; false if the system refused. */
        fun setClipboard(context: Context, mime: String, file: File): Boolean = runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val data = if (mime == ClipCodec.MIME_TEXT) {
                ClipData.newPlainText(CLIP_LABEL, file.readText())
            } else {
                ClipData.newUri(context.contentResolver, CLIP_LABEL, uriFor(context, file))
            }
            cm.setPrimaryClip(data)
        }.onFailure { Log.w(TAG, "setPrimaryClip refused", it) }.isSuccess
    }
}
