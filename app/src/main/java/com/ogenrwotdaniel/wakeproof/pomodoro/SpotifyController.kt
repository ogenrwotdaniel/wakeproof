package com.ogenrwotdaniel.wakeproof.pomodoro

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify hand-off without the Spotify SDK: deep links out (open the app or a
 * saved playlist) and media-key events back (play / pause / skip), so playback
 * can be driven from inside WakeProof — including while the phone is pinned to
 * the app during focus, when launching Spotify itself is blocked.
 *
 * Deliberately SDK-free: App Remote would need a registered Spotify developer
 * client ID and an OAuth round trip, neither of which this app has.
 *
 * Caveat of the media-key route: keys go to whichever media session is active
 * on the device, so they land on Spotify only when Spotify is the app that most
 * recently held audio focus.
 */
@Singleton
class SpotifyController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** True when the Spotify app is installed (needs the manifest `<queries>` entry). */
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null
    }.getOrDefault(false)

    /**
     * Opens [link] in Spotify — a share URL (`https://open.spotify.com/...`) or a
     * `spotify:` URI; blank just opens the app. Falls back to the web player, and
     * to the Play Store listing when Spotify is not installed. Returns false when
     * nothing at all could be opened.
     */
    fun open(link: String): Boolean {
        val target = link.trim()
        val attempts = buildList {
            if (target.isNotEmpty()) {
                add(viewIntent(target).setPackage(SPOTIFY_PACKAGE))
            }
            context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)?.let(::add)
            if (target.startsWith("http")) add(viewIntent(target)) // web player / browser
            add(viewIntent("https://play.google.com/store/apps/details?id=$SPOTIFY_PACKAGE"))
        }
        for (intent in attempts) {
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return true
        }
        return false
    }

    /** Resumes playback (idempotent — never toggles something already playing off). */
    fun play() = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)

    /** Pauses playback (idempotent). */
    fun pause() = dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)

    /** Blind toggle, for the UI button where we cannot know the current state. */
    fun playPause() = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    fun next() = dispatch(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous() = dispatch(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun viewIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        // Spotify's documented attribution extra for third-party deep links.
        .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://${context.packageName}"))

    private fun dispatch(keyCode: Int) {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
