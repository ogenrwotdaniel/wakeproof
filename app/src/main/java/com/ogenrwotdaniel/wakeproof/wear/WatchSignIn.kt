package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * Hands this device's sync account to the watch, so the watch can upload its
 * focus blocks and live timer to the cloud itself — and the tablet sees them
 * while this phone is somewhere else.
 *
 * Sends a fresh Google ID token over MessageClient ([WearContract.AUTH_PATH]);
 * the watch's `CloudAuthListenerService` exchanges it for its own Firebase
 * session at once. The token is valid for an hour and is never stored on
 * either side. The Data Layer only connects apps signed with the same key.
 */
@Singleton
class WatchSignIn @Inject constructor(@ApplicationContext private val context: Context) {

    /** How many connected watches accepted the token; 0 = none in reach. */
    suspend fun send(idToken: String): Int {
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }
            .getOrNull().orEmpty()
        val messages = Wearable.getMessageClient(context)
        val payload = idToken.toByteArray(Charsets.UTF_8)
        return nodes.count { node ->
            runCatching { messages.sendMessage(node.id, WearContract.AUTH_PATH, payload).await() }.isSuccess
        }
    }
}
