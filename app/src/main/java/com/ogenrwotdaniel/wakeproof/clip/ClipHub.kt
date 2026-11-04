package com.ogenrwotdaniel.wakeproof.clip

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
import com.ogenrwotdaniel.wakeproof.data.sync.CloudSyncAvailability
import com.ogenrwotdaniel.wakeproof.data.sync.SyncPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What became of a send. */
sealed interface ClipSendResult {
    /** Feature off or not paired. */
    data object Inactive : ClipSendResult
    data class Skipped(val reason: SendDecision) : ClipSendResult
    data class Sent(val lanAcks: Int, val viaCloud: Boolean) : ClipSendResult
    data class Failed(val message: String) : ClipSendResult
}

/**
 * The centre of WakeProof Clip (`docs/CLIPBOARD.md`): seals and sends local clips
 * (LAN to every known peer in parallel, Firestore as the fallback), and opens,
 * vets and delivers received ones. The always-on parts — LAN server, mDNS,
 * Firestore listener, presence upkeep, screenshot observer — are run by
 * [ClipRuntime] from `AppBlockService`.
 *
 * LAN works signed out; the cloud needs the sync account.
 */
@Singleton
class ClipHub @Inject constructor(
    @ApplicationContext private val context: Context,
    val keys: ClipKeyStore,
    private val syncPreferences: SyncPreferences,
    private val accounts: AccountRepository,
    private val availability: CloudSyncAvailability,
    private val firestore: FirebaseFirestore?
) {
    /** Outlives any one activity, so a send started from a finishing activity completes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val discovery = ClipDiscovery(context)
    private val delivery = ClipDelivery(context)
    private val state = context.getSharedPreferences("clip_state", Context.MODE_PRIVATE)
    private val lock = Any()
    private val presencePeers = ConcurrentHashMap<String, ClipPresence>()

    // ---- identity ----

    suspend fun installId(): String = syncPreferences.installId()

    /** The name the user gave the device, else the model. */
    fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Cloud access for the signed-in account, or null (signed out / no Firebase). */
    fun cloud(): ClipCloud? {
        val db = firestore ?: return null
        if (!availability.isAvailable) return null
        val uid = accounts.currentUid() ?: return null
        return ClipCloud(db, uid)
    }

    // ---- local bookkeeping ----

    /** Timestamp of the last clipboard description handled, so a home return reads only new clips. */
    var lastClipboardTimestamp: Long
        get() = state.getLong(KEY_LAST_TS, 0L)
        set(value) = state.edit { putLong(KEY_LAST_TS, value) }

    private var lastHash: String?
        get() = state.getString(KEY_LAST_HASH, null)
        set(value) = state.edit { putString(KEY_LAST_HASH, value) }

    private fun seenIds(): List<String> =
        state.getString(KEY_SEEN_IDS, null).orEmpty().split(',').filter { it.isNotBlank() }

    private fun rememberId(id: String) = synchronized(lock) {
        state.edit { putString(KEY_SEEN_IDS, ClipRules.remember(seenIds(), id).joinToString(",")) }
    }

    // ---- send ----

    /**
     * Sends a local clip. [explicit] = the user asked for this one (tile, share
     * sheet), which overrides only the "identical to the last clip" rule — a tap
     * that visibly does nothing reads as broken. Sensitive clips are never sent.
     */
    suspend fun send(mime: String, body: ByteArray, sensitive: Boolean = false, explicit: Boolean = false): ClipSendResult {
        val cfg = keys.current()
        val key = cfg.key
        if (!cfg.active || key == null) return ClipSendResult.Inactive
        val decision = synchronized(lock) {
            ClipRules.sendDecision(mime, body, sensitive, if (explicit) null else lastHash).also {
                if (it == SendDecision.SEND) lastHash = ClipCodec.hash(mime, body)
            }
        }
        if (decision != SendDecision.SEND) return ClipSendResult.Skipped(decision)

        return runCatching {
            val own = installId()
            val now = System.currentTimeMillis()
            val header = ClipHeader(UUID.randomUUID().toString(), own, deviceName(), now, mime)
            rememberId(header.id)
            val sealed = ClipCodec.seal(key, header, body)
            val cloud = cloud()
            if (cloud != null) refreshPresence(cloud, now)
            val acked = ClipLan.sendToPeers(peers(own, cfg), header.id, sealed)
            val presence = presencePeers.values.filter { it.installId != own }
                .associate { it.installId to it.updatedAt }
            var viaCloud = false
            if (cloud != null && !ClipRules.cloudSkippable(presence, acked, now)) {
                val cloudSealed = if (ClipCodec.needsCloudReencode(mime, body.size)) {
                    val jpeg = withContext(Dispatchers.Default) { ClipImages.toJpeg(body) }
                    if (jpeg != null) ClipCodec.seal(key, header.copy(mime = ClipCodec.MIME_JPEG), jpeg) else sealed
                } else {
                    sealed
                }
                cloud.upload(header.id, own, now, cloudSealed)
                viaCloud = true
                runCatching { cloud.housekeep(own, now) }.onFailure { Log.w(TAG, "housekeeping", it) }
            }
            Log.i(TAG, "sent ${header.id} $mime ${body.size} B: ${acked.size} LAN acks, cloud=$viaCloud")
            ClipSendResult.Sent(acked.size, viaCloud)
        }.getOrElse {
            Log.w(TAG, "send failed", it)
            ClipSendResult.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    private suspend fun refreshPresence(cloud: ClipCloud, now: Long) {
        val fresh = withTimeoutOrNull(PRESENCE_READ_TIMEOUT_MILLIS) {
            runCatching { cloud.presence(now - ClipRules.PRESENCE_FRESH_MILLIS) }.getOrNull()
        } ?: return
        presencePeers.clear()
        fresh.forEach { presencePeers[it.installId] = it }
    }

    /** Every LAN peer known from mDNS, presence docs and the pairing, merged by installId. */
    private fun peers(own: String, cfg: ClipConfig): List<ClipPeer> {
        val merged = HashMap<String, ClipPeer>()
        fun add(p: ClipPeer) {
            if (p.installId == own || p.addrs.isEmpty()) return
            val prev = merged[p.installId]
            merged[p.installId] = prev?.copy(addrs = prev.addrs + p.addrs) ?: p
        }
        discovery.peers.value.values.forEach(::add)
        presencePeers.values.forEach { add(ClipPeer(it.installId, it.name, it.lanAddrs.toSet(), it.port)) }
        val computer = cfg.computerInstallId
        if (computer != null) add(ClipPeer(computer, cfg.computerName ?: "Computer", cfg.computerAddrs.toSet()))
        return merged.values.toList()
    }

    // ---- receive ----

    /** A clip from the LAN server. Returns the HTTP status to answer with. */
    suspend fun receiveSealed(id: String, sealed: ByteArray): Int {
        val key = keys.current().takeIf { it.active }?.key ?: return 400
        val opened = try {
            ClipCodec.open(key, id, sealed)
        } catch (e: ClipRejected) {
            Log.w(TAG, "rejected $id: ${e.message}")
            return 400
        }
        accept(opened)
        return 204
    }

    /** A header from the Firestore listener: vetted on its doc fields before any chunk is read. */
    suspend fun receiveCloud(cloud: ClipCloud, header: CloudClipHeader) {
        val own = installId()
        val now = System.currentTimeMillis()
        if (header.origin == own || header.id in seenIds()) return
        if (now - header.createdAt > ClipRules.STALE_AFTER_MILLIS) return
        val sealed = runCatching { cloud.download(header) }.getOrNull() ?: return
        receiveSealed(header.id, sealed)
    }

    private suspend fun accept(opened: OpenedClip) {
        val h = opened.header
        val own = installId()
        val decision = synchronized(lock) {
            ClipRules.receiveDecision(h, own, seenIds(), System.currentTimeMillis())
        }
        rememberId(h.id)
        if (decision != ReceiveDecision.ACCEPT) {
            Log.d(TAG, "ignored ${h.id}: $decision")
            return
        }
        // Before delivering: the clipboard change it causes must not be sent back.
        synchronized(lock) { lastHash = ClipCodec.hash(h.mime, opened.body) }
        delivery.deliver(opened)
        Log.i(TAG, "received ${h.id} ${h.mime} from ${h.name}")
    }

    // ---- presence ----

    suspend fun writePresence() {
        val cloud = cloud() ?: return
        runCatching {
            cloud.writePresence(
                ClipPresence(
                    installId = installId(),
                    name = deviceName(),
                    platform = "android",
                    lanAddrs = ClipLan.ownAddresses(),
                    port = ClipLan.CLIP_PORT,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }.onFailure { Log.w(TAG, "presence", it) }
    }

    // ---- pairing ----

    /**
     * The pairing exchange: `POST /pair` to each address in [offer] until one
     * answers, then adopt K_final. [idToken] is null when signed out.
     */
    suspend fun pair(offer: PairOffer, idToken: String?): Result<PairResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val options = FirebaseApp.getApps(context).firstOrNull()?.options
            val existing = keys.current().key
            val request = PairRequest(
                installId = installId(),
                name = deviceName(),
                key = existing?.let(ClipCodec::b64url),
                idToken = idToken,
                apiKey = options?.apiKey,
                projectId = options?.projectId
            )
            val body = ClipCodec.sealPairing(offer.secret, ClipCodec.pairRequestJson(request).toByteArray())
            var lastError: Throwable? = null
            for (addr in offer.addrs) {
                val reply = runCatching { postPair(addr, offer.port, body) }
                    .onFailure { lastError = it }.getOrNull() ?: continue
                val response = ClipCodec.parsePairResponse(
                    String(ClipCodec.openPairing(offer.secret, reply), Charsets.UTF_8)
                )
                val key = ClipCodec.fromB64url(response.key)
                require(key.size == ClipCodec.KEY_BYTES) { "the computer sent a malformed key" }
                // The address that answered is the computer's known LAN address, so
                // LAN works with no mDNS and no cloud (the laptop does the same).
                keys.adoptPairing(key, response, listOf(addr))
                return@runCatching response
            }
            throw lastError ?: IllegalStateException("No address answered")
        }
    }

    private fun postPair(addr: String, port: Int, body: ByteArray): ByteArray {
        // Raw socket, not OkHttp: Android refuses cleartext HTTP to a LAN IP (see LanHttp).
        // 40 s read: the laptop may sign in to Firebase before it answers.
        val reply = LanHttp.post(addr, port, "/pair", body, connectTimeoutMs = 3_000, readTimeoutMs = 40_000)
        check(reply.code == 200) { "the computer answered ${reply.code}" }
        return reply.body
    }

    /** "Forget pairing": deletes K on this device only. */
    fun forget() {
        keys.forget()
        synchronized(lock) { state.edit { clear() } }
    }

    private companion object {
        const val TAG = "ClipHub"
        const val KEY_LAST_TS = "last_clipboard_ts"
        const val KEY_LAST_HASH = "last_hash"
        const val KEY_SEEN_IDS = "seen_ids"
        const val PRESENCE_READ_TIMEOUT_MILLIS = 3_000L
    }
}
