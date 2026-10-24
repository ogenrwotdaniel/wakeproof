package com.ogenrwotdaniel.wakeproof.data.sync

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Live cloud updates for an open dashboard, so a tablet watching from across the
 * room sees a watch session as it happens rather than at the next hourly pass.
 *
 * Two snapshot listeners, both alive only while someone collects [whileVisible]
 * (Home, while on screen):
 * * `live/watchTimer` — the watch's running pomodoro, published by the watch.
 * * the newest `completion_logs` document — when its server stamp is past this
 *   device's read cursor, something new was written (a block from the watch, a
 *   log from the phone) and an ordinary [SyncEngine.sync] pass brings it in.
 *   One document's worth of reads per change; the sync pass does the real work,
 *   so there is still exactly one code path that writes downloaded rows.
 *
 * Signed out, sync switched off, or no Firebase: emits null and listens to nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveCloudFeed @Inject constructor(
    private val firestore: FirebaseFirestore?,
    private val accounts: AccountRepository,
    private val preferences: SyncPreferences,
    private val syncEngine: SyncEngine
) {

    /** The watch's live timer, while also keeping completion logs current. */
    fun whileVisible(): Flow<LiveWatchTimer?> = channelFlow {
        launch { followRemoteLogs() }
        watchTimer().collect { send(it) }
    }

    private fun uidWhileSyncing(): Flow<String?> =
        combine(accounts.account, preferences.enabled) { account, enabled ->
            (account as? SyncAccount.SignedIn)?.uid?.takeIf { enabled }
        }

    private fun watchTimer(): Flow<LiveWatchTimer?> = uidWhileSyncing().flatMapLatest { uid ->
        val db = firestore
        if (uid == null || db == null) return@flatMapLatest flowOf(null)
        callbackFlow {
            val registration = db.collection(USERS).document(uid)
                .collection(LIVE).document(WATCH_TIMER)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Live watch timer listener failed", error)
                        return@addSnapshotListener
                    }
                    val fields = snapshot?.data
                    trySend(
                        fields?.let {
                            LiveWatchTimer.fromFields(it, snapshot.getTimestamp(SERVER_UPDATED_AT)?.toDate()?.time)
                        }
                    )
                }
            awaitClose { registration.remove() }
        }
    }

    private suspend fun followRemoteLogs() {
        uidWhileSyncing().flatMapLatest { uid ->
            val db = firestore
            if (uid == null || db == null) return@flatMapLatest flowOf<Timestamp?>(null)
            callbackFlow<Timestamp?> {
                val registration = db.collection(USERS).document(uid).collection(COMPLETION_LOGS)
                    .orderBy(SERVER_UPDATED_AT, Query.Direction.DESCENDING)
                    .limit(1)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Completion log listener failed", error)
                            return@addSnapshotListener
                        }
                        // A local write still in flight carries no server stamp yet.
                        if (snapshot == null || snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                        trySend(snapshot.documents.firstOrNull()?.getTimestamp(SERVER_UPDATED_AT))
                    }
                awaitClose { registration.remove() }
            }
        }
            // Conflated, not collectLatest: a sync pass is never cancelled half way,
            // and a burst of writes during one pass is answered by one more pass.
            .conflate()
            .collect { newest: Timestamp? ->
                if (newest == null) return@collect
                // A moment's grace lets a batch from the other device finish landing.
                delay(SETTLE_MILLIS)
                val cursor = preferences.cursor(COMPLETION_LOGS)
                if (cursor == null || newest > cursor) syncEngine.sync()
            }
    }

    private companion object {
        const val TAG = "LiveCloudFeed"
        const val USERS = "users"
        const val LIVE = "live"
        const val WATCH_TIMER = "watchTimer"
        const val COMPLETION_LOGS = "completion_logs"
        const val SERVER_UPDATED_AT = "serverUpdatedAt"
        const val SETTLE_MILLIS = 1_500L
    }
}
