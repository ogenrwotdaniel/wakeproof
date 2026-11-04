package com.ogenrwotdaniel.wakeproof.clip

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translucent, one-frame: puts a received clip on the clipboard from the
 * foreground. Opened from the clip notification when the background write was
 * refused (or to put an older image back).
 */
class ClipApplyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_FILE)
        val mime = intent.getStringExtra(EXTRA_MIME) ?: ClipCodec.MIME_TEXT
        // Only a bare file name from our own cache dir; never a path from outside.
        val file = name?.takeIf { '/' !in it }?.let { File(ClipDelivery.cacheDir(this), it) }
        val ok = file != null && file.exists() && ClipDelivery.setClipboard(this, mime, file)
        Toast.makeText(
            this,
            if (ok) "Copied — paste anywhere." else "That clip is no longer available.",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_MIME = "mime"

        fun intent(context: Context, fileName: String, mime: String): Intent =
            Intent(context, ClipApplyActivity::class.java)
                .putExtra(EXTRA_FILE, fileName)
                .putExtra(EXTRA_MIME, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Translucent, one-frame: reads the clipboard once it has window focus (the only
 * moment Android allows it), sends it, finishes. Launched by [ClipTileService].
 */
@AndroidEntryPoint
class ClipSendActivity : ComponentActivity() {

    @Inject lateinit var hub: ClipHub
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        ClipTriggers.sendClipboardNow(this, hub)
        finish()
    }
}

/**
 * Share target "Send to my devices": text and images from any app's share sheet,
 * single or multiple. The share grants read access to the URIs, so no focus or
 * clipboard access is involved.
 */
@AndroidEntryPoint
class ClipShareActivity : ComponentActivity() {

    @Inject lateinit var hub: ClipHub

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clips = sharedClips(intent)
        val app = applicationContext
        if (!hub.keys.current().active) {
            Toast.makeText(app, "Clipboard across devices is off — pair a computer in Settings.", Toast.LENGTH_LONG).show()
        } else if (clips.isEmpty()) {
            Toast.makeText(app, "Nothing here can be sent — only text and images.", Toast.LENGTH_SHORT).show()
        } else {
            // Read the images before finishing: the share's URI grant is this activity's.
            lifecycleScope.launch {
                val ready: List<Pair<String, ByteArray>?> = withContext(Dispatchers.IO) {
                    clips.map { clip ->
                        when (clip) {
                            is LocalClip.Text -> ClipCodec.MIME_TEXT to clip.text.toByteArray(Charsets.UTF_8)
                            is LocalClip.Image -> ClipTriggers.readImage(app, clip.uri, clip.mimeHint)
                        }
                    }
                }
                hub.scope.launch {
                    val last = ready.map { item ->
                        if (item == null) {
                            ClipSendResult.Failed("couldn't read the image")
                        } else {
                            hub.send(item.first, item.second, explicit = true)
                        }
                    }.lastOrNull()
                    if (last != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(app, ClipTriggers.describe(last), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                finish()
            }
            return
        }
        finish()
    }

    private fun sharedClips(intent: Intent): List<LocalClip> {
        val type = intent.type.orEmpty()
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            ).orEmpty()
            else -> emptyList()
        }
        val images = uris.map { LocalClip.Image(it, type.takeIf { t -> t.startsWith("image/") }) }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() && uris.isEmpty() }
            ?.let { LocalClip.Text(it) }
        return images + listOfNotNull(text)
    }
}

/** Quick Settings tile "Send clipboard": opens [ClipSendActivity], which can read the clipboard. */
@AndroidEntryPoint
class ClipTileService : TileService() {

    @Inject lateinit var hub: ClipHub

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (hub.keys.current().active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pending)
    }
}
