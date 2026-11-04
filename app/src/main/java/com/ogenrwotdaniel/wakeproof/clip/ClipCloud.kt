package com.ogenrwotdaniel.wakeproof.clip

import android.util.Log
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/** A peer's `presence_<installId>` doc. */
data class ClipPresence(
    val installId: String,
    val name: String,
    val platform: String,
    val lanAddrs: List<String>,
    val port: Int,
    val updatedAt: Long
)

/** A `clip_<id>` header doc as it arrives from the listener. */
data class CloudClipHeader(val id: String, val origin: String, val createdAt: Long, val chunks: Int)

/**
 * The Firestore half of `docs/CLIPBOARD.md`: `users/{uid}/clips/{docId}`, flat.
 * Ciphertext only — the doc fields name the clip, the `data` bytes are sealed.
 */
class ClipCloud(private val db: FirebaseFirestore, private val uid: String) {

    private val clips get() = db.collection(USERS).document(uid).collection(COLLECTION)

    /** Writes every chunk, THEN the header, so a reader who sees the header can fetch them all. */
    suspend fun upload(id: String, origin: String, createdAt: Long, sealed: ByteArray) {
        val expiresAt = createdAt + EXPIRY_MILLIS
        val chunks = ClipCodec.chunk(sealed)
        chunks.forEachIndexed { i, data ->
            clips.document(chunkDoc(id, i)).set(
                mapOf(
                    F_ID to id,
                    F_INDEX to i.toLong(),
                    F_DATA to Blob.fromBytes(data),
                    F_EXPIRES_AT to expiresAt
                )
            ).await()
        }
        clips.document(HEADER_PREFIX + id).set(
            mapOf(
                F_ID to id,
                F_ORIGIN to origin,
                F_CREATED_AT to createdAt,
                F_CHUNKS to chunks.size.toLong(),
                F_SIZE to sealed.size.toLong(),
                F_EXPIRES_AT to expiresAt
            )
        ).await()
    }

    /** Fetches and joins the chunks of a header; null if any is missing. */
    suspend fun download(header: CloudClipHeader): ByteArray? {
        val parts = (0 until header.chunks).map { i ->
            val doc = clips.document(chunkDoc(header.id, i)).get().await()
            doc.getBlob(F_DATA)?.toBytes() ?: return null
        }
        return ClipCodec.join(parts)
    }

    /** Deletes this device's own expired clips and all but its 10 newest (header first). */
    suspend fun housekeep(origin: String, now: Long) {
        val own = clips.whereEqualTo(F_ORIGIN, origin).get().await().documents.mapNotNull { d ->
            OwnCloudClip(
                id = d.getString(F_ID) ?: return@mapNotNull null,
                createdAt = d.getLong(F_CREATED_AT) ?: 0L,
                expiresAt = d.getLong(F_EXPIRES_AT) ?: 0L,
                chunks = (d.getLong(F_CHUNKS) ?: 0L).toInt()
            )
        }
        val doomed = ClipRules.clipsToDelete(own, now).toSet()
        own.filter { it.id in doomed }.forEach { clip ->
            runCatching {
                clips.document(HEADER_PREFIX + clip.id).delete().await()
                repeat(clip.chunks) { i -> clips.document(chunkDoc(clip.id, i)).delete().await() }
            }.onFailure { Log.w(TAG, "housekeeping ${clip.id}", it) }
        }
    }

    suspend fun writePresence(presence: ClipPresence) {
        clips.document(PRESENCE_PREFIX + presence.installId).set(
            mapOf(
                F_INSTALL_ID to presence.installId,
                F_NAME to presence.name,
                F_PLATFORM to presence.platform,
                F_LAN_ADDRS to presence.lanAddrs,
                F_PORT to presence.port.toLong(),
                F_UPDATED_AT to presence.updatedAt
            )
        ).await()
    }

    /** Every presence doc updated since [since] (only presence docs carry `updatedAt`). */
    suspend fun presence(since: Long): List<ClipPresence> =
        clips.whereGreaterThan(F_UPDATED_AT, since).get().await().documents.mapNotNull { d ->
            ClipPresence(
                installId = d.getString(F_INSTALL_ID) ?: return@mapNotNull null,
                name = d.getString(F_NAME) ?: "Device",
                platform = d.getString(F_PLATFORM) ?: "",
                lanAddrs = (d.get(F_LAN_ADDRS) as? List<*>).orEmpty().filterIsInstance<String>(),
                port = (d.getLong(F_PORT) ?: ClipLan.CLIP_PORT.toLong()).toInt(),
                updatedAt = d.getLong(F_UPDATED_AT) ?: 0L
            )
        }

    /**
     * Listens for `clip_` headers created after [since]; only headers carry
     * `createdAt`, so this needs no composite index.
     */
    fun listen(since: Long, onHeader: (CloudClipHeader) -> Unit): ListenerRegistration =
        clips.whereGreaterThan(F_CREATED_AT, since)
            .orderBy(F_CREATED_AT, Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "clip listener", error)
                    return@addSnapshotListener
                }
                snapshot?.documentChanges.orEmpty()
                    .filter { it.type == DocumentChange.Type.ADDED }
                    .forEach { change ->
                        val d = change.document
                        val id = d.getString(F_ID) ?: return@forEach
                        onHeader(
                            CloudClipHeader(
                                id = id,
                                origin = d.getString(F_ORIGIN).orEmpty(),
                                createdAt = d.getLong(F_CREATED_AT) ?: 0L,
                                chunks = (d.getLong(F_CHUNKS) ?: 0L).toInt()
                            )
                        )
                    }
            }

    companion object {
        private const val TAG = "ClipCloud"
        const val USERS = "users"
        const val COLLECTION = "clips"
        const val HEADER_PREFIX = "clip_"
        const val PRESENCE_PREFIX = "presence_"
        const val EXPIRY_MILLIS = 60 * 60 * 1000L
        const val F_ID = "id"
        const val F_ORIGIN = "origin"
        const val F_CREATED_AT = "createdAt"
        const val F_CHUNKS = "chunks"
        const val F_SIZE = "size"
        const val F_EXPIRES_AT = "expiresAt"
        const val F_INDEX = "index"
        const val F_DATA = "data"
        const val F_INSTALL_ID = "installId"
        const val F_NAME = "name"
        const val F_PLATFORM = "platform"
        const val F_LAN_ADDRS = "lanAddrs"
        const val F_PORT = "port"
        const val F_UPDATED_AT = "updatedAt"

        fun chunkDoc(id: String, index: Int) = "chunk_${id}_$index"
    }
}
