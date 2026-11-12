package com.ogenrwotdaniel.wakeproof.data.sync

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The summary another of the user's devices still owes, as last heard; 0 = none. */
object PeerSummaryHold {
    @Volatile var untilMillis = 0L
}

/**
 * The phone's owed session summary, told to the user's other devices
 * (2026-11-13): while it is owed the tablet starts no session either, or a
 * session there would be the way to never write it.
 *
 * One document of its own, `users/{uid}/live/summaryHold`: until when the
 * summary is owed (wall clock, 0 = settled) and which install wrote it. Not a
 * field of `live/focusSession`: a tablet that followed the session writes that
 * document at the same moment the session ends and would wipe the field out.
 * A device ignores its own document.
 *
 * Best-effort, like the rest of the live layer: signed out, sync off or no
 * Firebase, and only the phone itself is held.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveSummaryHold @Inject constructor(
    private val firestore: FirebaseFirestore?,
    private val accounts: AccountRepository,
    private val preferences: SyncPreferences
) {
    // Its own scope: the timer service that asks for the summary stops in the
    // same breath, and a write launched on its scope would be cancelled unsent.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Announces that this device's summary is owed until [untilMillis] (0 =
     * settled). Never waited for: an offline phone must not hang on it.
     */
    fun publish(untilMillis: Long) {
        scope.launch {
            val db = firestore ?: return@launch
            runCatching {
                val uid = accounts.currentUid() ?: return@launch
                if (!preferences.isEnabled()) return@launch
                db.collection(USERS).document(uid).collection(LIVE).document(SUMMARY_HOLD)
                    .set(
                        mapOf(
                            UNTIL to untilMillis,
                            INSTALL_ID to preferences.installId(),
                            SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                        )
                    )
            }.onFailure { Log.w(TAG, "Summary hold publish failed", it) }
        }
    }

    /** Until when another device's summary is owed; 0 when none is known. Emits on every change. */
    fun peerUntil(): Flow<Long> =
        combine(accounts.account, preferences.enabled) { account, enabled ->
            (account as? SyncAccount.SignedIn)?.uid?.takeIf { enabled }
        }.flatMapLatest { uid ->
            val db = firestore
            if (uid == null || db == null) return@flatMapLatest flowOf(0L)
            val self = preferences.installId()
            callbackFlow {
                val registration = db.collection(USERS).document(uid)
                    .collection(LIVE).document(SUMMARY_HOLD)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Summary hold listener failed", error)
                            // Nothing more will be heard: let go, or the last
                            // hold heard would refuse starts after it is settled.
                            trySend(0L)
                            return@addSnapshotListener
                        }
                        val from = snapshot?.getString(INSTALL_ID)
                        trySend(snapshot?.getLong(UNTIL)?.takeIf { from != null && from != self } ?: 0L)
                    }
                awaitClose { registration.remove() }
            }
        }

    private companion object {
        const val TAG = "LiveSummaryHold"
        const val USERS = "users"
        const val LIVE = "live"
        const val SUMMARY_HOLD = "summaryHold"
        const val UNTIL = "until"
        const val INSTALL_ID = "installId"
        const val SERVER_UPDATED_AT = "serverUpdatedAt"
    }
}
