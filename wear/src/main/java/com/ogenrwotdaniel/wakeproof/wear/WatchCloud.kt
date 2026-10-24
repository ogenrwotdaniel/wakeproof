package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * The watch's own line to the cloud: finished focus blocks and the live timer
 * go straight to Firestore over the watch's Wi-Fi/LTE, so the tablet sees them
 * while the phone is somewhere else entirely.
 *
 * ### Additive, not a replacement
 *
 * The Data Layer path to the phone ([FocusSessionOutbox]) is untouched. Both
 * copies of a block carry the same id, which is the row's `syncId` on every
 * device, so whichever arrives first wins and the other collapses onto it. The
 * phone's acknowledgement list is read off its own table, so a block the phone
 * first sees via cloud sync is acknowledged exactly like one it imported.
 *
 * ### Firebase by hand
 *
 * There is no google-services plugin in this module (see wear/build.gradle.kts):
 * the options come from BuildConfig and the default app is initialised here, on
 * first use. A build without `app/google-services.json` has blank values and
 * [configured] is false — every call below is then a no-op.
 *
 * ### Signing in
 *
 * Same Google account as the phone, so the same `users/{uid}` tree. Either on
 * the watch (Credential Manager, from [MainActivity]) or handed over from the
 * phone as a fresh ID token ([CloudAuthListenerService]). Firebase keeps the
 * session on the watch afterwards; neither is needed again.
 */
internal object WatchCloud {

    private const val TAG = "WatchCloud"

    val configured: Boolean
        get() = BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank()

    val canSignInHere: Boolean get() = configured && BuildConfig.WEB_CLIENT_ID.isNotBlank()

    @Volatile private var firestore: FirebaseFirestore? = null

    private fun app(context: Context): FirebaseApp? {
        if (!configured) return null
        synchronized(this) {
            FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?.let { return it }
            val options = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .build()
            return runCatching { FirebaseApp.initializeApp(context.applicationContext, options) }
                .onFailure { Log.w(TAG, "Firebase init failed", it) }
                .getOrNull()
        }
    }

    fun auth(context: Context): FirebaseAuth? = app(context)?.let { FirebaseAuth.getInstance(it) }

    private fun db(context: Context): FirebaseFirestore? {
        firestore?.let { return it }
        val app = app(context) ?: return null
        synchronized(this) {
            firestore?.let { return it }
            return FirebaseFirestore.getInstance(app).apply {
                // Memory only, as on the phone: the durable queue is [CloudOutbox],
                // and a watch has no storage to spare for a second one.
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
            }.also { firestore = it }
        }
    }

    /** The signed-in account's email (or uid), or null when signed out. */
    fun accountLabel(context: Context): String? =
        auth(context)?.currentUser?.let { it.email ?: it.uid }

    fun signedIn(context: Context): Boolean = auth(context)?.currentUser != null

    /** Exchanges a Google ID token for a Firebase session on the watch. */
    suspend fun signIn(context: Context, googleIdToken: String): Result<String> = runCatching {
        val auth = auth(context) ?: error("This build has no cloud configuration.")
        val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null))
            .await().user ?: error("Firebase returned no user.")
        // Publish the current timer straight away, so the tablet does not wait
        // for the next phase change to learn one is running.
        CloudOutbox.kick(context)
        user.email ?: user.uid
    }

    fun signOut(context: Context) {
        auth(context)?.signOut()
        CloudOutbox.clear(context)
    }

    /**
     * Writes [block] only if no document with its id exists yet. A blind set
     * could resurrect a block deleted on another device since (the tombstone
     * would be overwritten with `deleted = false`), or roll back a newer copy.
     */
    suspend fun upload(context: Context, block: FocusBlock): Boolean {
        val uid = auth(context)?.currentUser?.uid ?: return false
        val db = db(context) ?: return false
        val ref = db.collection(CloudContract.USERS).document(uid)
            .collection(CloudContract.COMPLETION_LOGS).document(block.id)
        val body = CloudDocuments.block(block) +
            (CloudContract.SERVER_UPDATED_AT to FieldValue.serverTimestamp())
        return runCatching {
            withTimeout(NETWORK_TIMEOUT_MS) {
                db.runTransaction { tx ->
                    if (!tx.get(ref).exists()) tx.set(ref, body)
                }.await()
            }
        }.onFailure { Log.w(TAG, "Block ${block.id} not uploaded", it) }.isSuccess
    }

    suspend fun publishTimer(context: Context, state: WatchTimerState): Boolean {
        val uid = auth(context)?.currentUser?.uid ?: return false
        val db = db(context) ?: return false
        val body = CloudDocuments.liveTimer(state, System.currentTimeMillis()) +
            (CloudContract.SERVER_UPDATED_AT to FieldValue.serverTimestamp())
        return runCatching {
            withTimeout(NETWORK_TIMEOUT_MS) {
                db.collection(CloudContract.USERS).document(uid)
                    .collection(CloudContract.LIVE).document(CloudContract.WATCH_TIMER)
                    .set(body, SetOptions.merge()).await()
            }
        }.onFailure { Log.w(TAG, "Live timer not published", it) }.isSuccess
    }

    private const val NETWORK_TIMEOUT_MS = 30_000L
}

/**
 * Blocks waiting to go to the cloud, plus the "the timer changed" signal, drained
 * by [CloudUploadWorker] whenever the watch has a network.
 *
 * Its own SharedPreferences queue rather than Firestore's offline write queue:
 * that one lives in memory here, and a watch reaps an idle process within
 * minutes — a block ended in a lift with no signal would be gone. WorkManager
 * with a network constraint does the waiting.
 *
 * Only blocks finished while SIGNED IN are queued. A signed-out watch would
 * otherwise grow this forever, and its blocks still reach the cloud through the
 * phone, as they always have.
 */
internal object CloudOutbox {

    private const val FILE = "cloud_outbox"
    private const val KEY_UNSENT = "unsent"
    private const val KEY_LIVE_SENT = "live_sent"
    private const val WORK = "wakeproof_cloud_upload"

    /** Blocks older than this are dropped unsent: the phone has had two weeks. */
    private const val MAX_AGE_DAYS = 14L

    fun enqueue(context: Context, block: FocusBlock) {
        if (!WatchCloud.signedIn(context)) return
        val prefs = prefs(context)
        synchronized(this) {
            val unsent = prefs.getStringSet(KEY_UNSENT, emptySet()).orEmpty() +
                FocusSessionOutbox.encode(block)
            prefs.edit(commit = true) { putStringSet(KEY_UNSENT, unsent) }
        }
        kick(context)
    }

    fun waiting(context: Context): Int = prefs(context).getStringSet(KEY_UNSENT, emptySet()).orEmpty().size

    /**
     * Schedules a drain. REPLACE: a run already waiting for the network is
     * superseded by one that will read the newest state anyway, so a burst of
     * timer taps is one write, not five.
     */
    fun kick(context: Context) {
        if (!WatchCloud.signedIn(context)) return
        val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun clear(context: Context) {
        prefs(context).edit(commit = true) { clear() }
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
    }

    /** Uploads what it can; returns whether anything is left for a retry. */
    suspend fun drain(context: Context): Boolean {
        val prefs = prefs(context)
        val oldest = java.time.LocalDate.now().toEpochDay() - MAX_AGE_DAYS
        val unsent = prefs.getStringSet(KEY_UNSENT, emptySet()).orEmpty()
        val done = unsent.filter { raw ->
            val block = FocusSessionOutbox.decode(raw) ?: return@filter true
            block.epochDay < oldest || WatchCloud.upload(context, block)
        }.toSet()
        if (done.isNotEmpty()) {
            synchronized(this) {
                // Re-read: a block may have been enqueued while these uploaded.
                val now = prefs.getStringSet(KEY_UNSENT, emptySet()).orEmpty()
                prefs.edit(commit = true) { putStringSet(KEY_UNSENT, now - done) }
            }
        }

        val state = WatchTimerStore.read(context)
        val key = CloudDocuments.liveTimerKey(state)
        var liveLeft = false
        if (prefs.getString(KEY_LIVE_SENT, null) != key) {
            if (WatchCloud.publishTimer(context, state)) {
                prefs.edit(commit = true) { putString(KEY_LIVE_SENT, key) }
            } else {
                liveLeft = true
            }
        }
        return liveLeft || waiting(context) > 0
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** Drains [CloudOutbox] once the watch has a network; retries with backoff. */
class CloudUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!WatchCloud.signedIn(applicationContext)) return Result.success()
        val left = CloudOutbox.drain(applicationContext)
        return if (left && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        /** Past this a later kick (next block, next app open) starts a fresh run. */
        const val MAX_ATTEMPTS = 8
    }
}
