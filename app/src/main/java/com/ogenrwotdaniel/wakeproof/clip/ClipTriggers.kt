package com.ogenrwotdaniel.wakeproof.clip

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A clipboard item as read while focused; the bytes are fetched later, off the main thread. */
sealed interface LocalClip {
    data class Text(val text: String) : LocalClip
    data class Image(val uri: Uri, val mimeHint: String?) : LocalClip
}

/**
 * The ways a local clip leaves this device: the home screen regaining focus, the
 * Quick Settings tile, the share sheet and screenshots. Android lets an app READ
 * the clipboard only while it has window focus, which is why the first two read
 * from `onWindowFocusChanged`.
 */
object ClipTriggers {
    private const val TAG = "ClipTriggers"

    /**
     * Home screen focused: if the clipboard changed since the last clip this device
     * handled, read and send it. Sensitive clips are not even read, so no
     * clipboard-access toast is raised for a password.
     */
    fun onHomeFocused(context: Context, hub: ClipHub) {
        if (!hub.keys.current().active) return
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val description = cm.primaryClipDescription ?: return
        val stamp = description.timestamp
        if (stamp <= hub.lastClipboardTimestamp) return
        hub.lastClipboardTimestamp = stamp
        if (isSensitive(description)) return
        val clip = readPrimary(context, cm) ?: return
        hub.scope.launch { sendLocal(context, hub, clip, explicit = false) }
    }

    /** Tile / explicit: read now and send even if it equals the last clip. */
    fun sendClipboardNow(context: Context, hub: ClipHub) {
        val app = context.applicationContext
        if (!hub.keys.current().active) {
            toast(app, "Clipboard across devices is off — pair a computer in Settings.")
            return
        }
        val cm = context.getSystemService(ClipboardManager::class.java)
        val description = cm?.primaryClipDescription
        if (cm == null || description == null) {
            toast(app, "The clipboard is empty.")
            return
        }
        hub.lastClipboardTimestamp = maxOf(hub.lastClipboardTimestamp, description.timestamp)
        if (isSensitive(description)) {
            toast(app, "Not sent: the clipboard holds something marked sensitive.")
            return
        }
        val clip = readPrimary(context, cm)
        if (clip == null) {
            toast(app, "Nothing on the clipboard can be sent.")
            return
        }
        hub.scope.launch {
            val result = sendLocal(app, hub, clip, explicit = true)
            withContext(Dispatchers.Main) { toast(app, describe(result)) }
        }
    }

    /** Sends [clip], reading an image's bytes first. */
    suspend fun sendLocal(context: Context, hub: ClipHub, clip: LocalClip, explicit: Boolean): ClipSendResult =
        when (clip) {
            is LocalClip.Text -> hub.send(ClipCodec.MIME_TEXT, clip.text.toByteArray(Charsets.UTF_8), explicit = explicit)
            is LocalClip.Image -> {
                val image = withContext(Dispatchers.IO) { readImage(context, clip.uri, clip.mimeHint) }
                if (image == null) {
                    ClipSendResult.Failed("couldn't read the image")
                } else {
                    hub.send(image.first, image.second, explicit = explicit)
                }
            }
        }

    /** An image URI as a protocol mime + bytes; PNG/JPEG untouched, other formats as PNG. */
    fun readImage(context: Context, uri: Uri, mimeHint: String?): Pair<String, ByteArray>? = runCatching {
        val mime = context.contentResolver.getType(uri) ?: mimeHint
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            ClipImages.readCapped(it, ClipCodec.MAX_IMAGE_BYTES)
        } ?: return null
        ClipImages.normalise(mime, bytes)
    }.onFailure { Log.w(TAG, "read image $uri", it) }.getOrNull()

    fun describe(result: ClipSendResult): String = when (result) {
        ClipSendResult.Inactive -> "Clipboard across devices is off."
        is ClipSendResult.Failed -> "Not sent: ${result.message}"
        is ClipSendResult.Skipped -> when (result.reason) {
            SendDecision.SENSITIVE -> "Not sent: marked sensitive."
            SendDecision.EMPTY -> "Not sent: it's empty."
            SendDecision.UNSUPPORTED -> "Not sent: only text and images travel."
            SendDecision.TOO_BIG -> "Not sent: too big."
            SendDecision.DUPLICATE -> "Already sent."
            SendDecision.SEND -> "Sent."
        }
        is ClipSendResult.Sent -> when {
            result.lanAcks > 0 && result.viaCloud -> "Sent to ${result.lanAcks} nearby + the cloud."
            result.lanAcks > 0 -> "Sent to ${plural(result.lanAcks)} nearby."
            result.viaCloud -> "Sent through the cloud."
            else -> "No device answered, and the cloud needs sign-in."
        }
    }

    private fun plural(n: Int) = if (n == 1) "1 device" else "$n devices"

    private fun isSensitive(description: ClipDescription): Boolean =
        description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true

    private fun readPrimary(context: Context, cm: ClipboardManager): LocalClip? {
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val description = clip.description
        val imageMime = (0 until description.mimeTypeCount)
            .map { description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        val uri = item.uri
        if (uri != null && imageMime != null) return LocalClip.Image(uri, imageMime)
        val text = item.text?.toString()
            ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()
        return text?.let { LocalClip.Text(it) }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
