package com.ogenrwotdaniel.wakeproof.data.sync

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * A focus session on one device walls the others too (2026-10-28). The tablet
 * was a second, unguarded phone: start a session on the phone, pick up the
 * tablet, and every app on it was open.
 *
 * One document, `users/{uid}/live/focusSession`: when the session ends
 * ([ENDS_AT], wall clock) and which install started it. Sessions cannot be
 * stopped, so an end time is all a peer needs — it can wall until then without
 * hearing from the session's device again, which matters when that device is
 * in a bag with no signal. A device ignores its own document.
 *
 * Best-effort, like the rest of the live layer: signed out, sync off, or no
 * Firebase, and each device only walls itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveFocusSession @Inject constructor(
    private val firestore: FirebaseFirestore?,
    private val accounts: AccountRepository,
    private val preferences: SyncPreferences
) {

    /** This device's own session end; 0 = none. */
    @Volatile private var ownEndsAt = 0L

    /** The paired watch's session end, relayed by this phone; 0 = none. */
    @Volatile private var watchEndsAt = 0L

    /** Announces a session on this device running until [endsAtMillis]; 0 = ended. */
    suspend fun publish(endsAtMillis: Long) {
        ownEndsAt = endsAtMillis
        write()
    }

    /**
     * Passes the watch's session on to the tablet (2026-11-01): the watch talks
     * to this phone over Bluetooth, and the tablet only hears the cloud. Shares
     * the one document with this device's own session, so whichever ends later
     * is what the peers wall until — the phone's own session ending must not
     * release the tablet while the watch's still runs.
     */
    suspend fun relayWatch(endsAtMillis: Long) {
        if (watchEndsAt == endsAtMillis) return
        watchEndsAt = endsAtMillis
        write()
    }

    /**
     * Withdraws a session THIS install announced but no longer runs
     * (2026-11-04): a process killed mid-session never published its end, so
     * the tablet stayed walled until the dead session's end time. Only our own
     * document is touched — a peer's session must keep walling us.
     */
    suspend fun releaseIfOwnStale() {
        val db = firestore ?: return
        val uid = accounts.currentUid() ?: return
        if (!preferences.isEnabled()) return
        runCatching {
            val doc = db.collection(USERS).document(uid).collection(LIVE).document(FOCUS_SESSION).get().await()
            val from = doc.getString(INSTALL_ID)
            val endsAt = doc.getLong(ENDS_AT) ?: 0L
            if (from == preferences.installId() && endsAt > System.currentTimeMillis()) publish(0L)
        }.onFailure { Log.w(TAG, "Stale focus session release failed", it) }
    }

    private suspend fun write() {
        val now = System.currentTimeMillis()
        val endsAtMillis = maxOf(ownEndsAt, watchEndsAt.takeIf { it > now } ?: 0L)
        val db = firestore ?: return
        val uid = accounts.currentUid() ?: return
        if (!preferences.isEnabled()) return
        runCatching {
            db.collection(USERS).document(uid).collection(LIVE).document(FOCUS_SESSION)
                .set(
                    mapOf(
                        ENDS_AT to endsAtMillis,
                        INSTALL_ID to preferences.installId(),
                        SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                    )
                ).await()
        }.onFailure { Log.w(TAG, "Focus session publish failed", it) }
    }

    /**
     * When another device's session ends (wall clock), or null when none is
     * known. Emits again on every change; the reader compares with the clock.
     */
    fun peerSessionEndsAt(): Flow<Long?> =
        combine(accounts.account, preferences.enabled) { account, enabled ->
            (account as? SyncAccount.SignedIn)?.uid?.takeIf { enabled }
        }.flatMapLatest { uid ->
            val db = firestore
            if (uid == null || db == null) return@flatMapLatest flowOf(null)
            val self = preferences.installId()
            callbackFlow {
                val registration = db.collection(USERS).document(uid)
                    .collection(LIVE).document(FOCUS_SESSION)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Focus session listener failed", error)
                            return@addSnapshotListener
                        }
                        val from = snapshot?.getString(INSTALL_ID)
                        trySend(snapshot?.getLong(ENDS_AT)?.takeIf { from != null && from != self })
                    }
                awaitClose { registration.remove() }
            }
        }

    private companion object {
        const val TAG = "LiveFocusSession"
        const val USERS = "users"
        const val LIVE = "live"
        const val FOCUS_SESSION = "focusSession"
        const val ENDS_AT = "endsAt"
        const val INSTALL_ID = "installId"
        const val SERVER_UPDATED_AT = "serverUpdatedAt"
    }
}
