package com.ogenrwotdaniel.wakeproof.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Receives the phone's "sign the watch in" hand-off (`/wakeproof/auth`): a
 * fresh Google ID token for the account the phone syncs with, exchanged here
 * for the watch's own Firebase session.
 *
 * The token is short-lived (an hour) and single-purpose; it is used at once and
 * never stored. The Data Layer only connects apps signed with the same key, so
 * nothing but WakeProof on the user's own phone can send one.
 */
class CloudAuthListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != CloudContract.AUTH_PATH) return
        val token = String(messageEvent.data, Charsets.UTF_8)
        if (token.isBlank()) return
        // Blocking is intended: this runs on a background thread and the service
        // may be torn down the moment it returns.
        runBlocking {
            WatchCloud.signIn(applicationContext, token)
                .onSuccess { Log.d(TAG, "Signed in from the phone as $it") }
                .onFailure { Log.w(TAG, "Sign-in from the phone failed", it) }
        }
    }

    private companion object {
        const val TAG = "CloudAuthListener"
    }
}
