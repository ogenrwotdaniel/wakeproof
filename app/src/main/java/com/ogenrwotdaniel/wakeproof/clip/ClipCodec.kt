package com.ogenrwotdaniel.wakeproof.clip

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The header sealed in front of every clip body — `docs/CLIPBOARD.md`, "The sealed clip".
 * Field order here is the wire order; [ClipCodec.headerJson] writes it by hand so the
 * bytes match the Python side exactly.
 */
data class ClipHeader(
    val id: String,
    val origin: String,
    val name: String,
    val createdAt: Long,
    val mime: String,
    val v: Int = ClipCodec.VERSION
)

/** A clip that decrypted and whose header matched its transport id. */
class OpenedClip(val header: ClipHeader, val body: ByteArray)

/** A sealed clip that must be dropped: wrong key, tampered, id mismatch or malformed. */
class ClipRejected(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What a pairing QR (`wpclip1:` …) offers: where the laptop listens and the one-off secret. */
data class PairOffer(
    val addrs: List<String>,
    val port: Int,
    val secret: ByteArray,
    val name: String
)

/** The laptop's encrypted reply to `POST /pair`. */
@Serializable
data class PairResponse(
    val key: String,
    val installId: String,
    val name: String,
    val uid: String? = null
)

/** The plaintext of `POST /pair`, keys in the spec's order. */
@Serializable
data class PairRequest(
    val installId: String,
    val name: String,
    val key: String?,
    val idToken: String?,
    val apiKey: String?,
    val projectId: String?
)

/**
 * Pure crypto + framing for WakeProof Clip (wire protocol v1, `docs/CLIPBOARD.md`).
 *
 * No Android types, so every byte is checked on the JVM against the shared vectors
 * in `app/src/test/resources/clip/vectors.json`, which the Python side reads too.
 * Image re-encoding (a Bitmap concern) lives in [ClipImages]; only the decision to
 * do it, [needsCloudReencode], is here.
 */
object ClipCodec {
    const val VERSION = 1
    const val MIME_TEXT = "text/plain"
    const val MIME_PNG = "image/png"
    const val MIME_JPEG = "image/jpeg"
    val SUPPORTED_MIMES = setOf(MIME_TEXT, MIME_PNG, MIME_JPEG)

    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BYTES = 32
    const val CHUNK_BYTES = 700 * 1024
    const val MAX_CHUNKS = 32
    const val MAX_TEXT_BYTES = 256 * 1024
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    const val CLOUD_REENCODE_ABOVE_BYTES = 2 * 1024 * 1024
    const val CLOUD_JPEG_QUALITY = 90
    const val QR_PREFIX = "wpclip1:"

    private const val CLIP_AAD_PREFIX = "wakeproof-clip-v1|"
    private const val PAIR_AAD = "wakeproof-pair-v1"

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)
    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    // ---- base64url, no padding ----

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Decodes base64url with or without padding. */
    fun fromB64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))

    // ---- header ----

    /**
     * The header exactly as the spec writes it: keys in order, no whitespace,
     * non-ASCII raw. Escaping follows Python's `json.dumps(ensure_ascii=False)`.
     */
    fun headerJson(h: ClipHeader): String = buildString {
        append("{\"v\":").append(h.v)
        append(",\"id\":").appendJsonString(h.id)
        append(",\"origin\":").appendJsonString(h.origin)
        append(",\"name\":").appendJsonString(h.name)
        append(",\"createdAt\":").append(h.createdAt)
        append(",\"mime\":").appendJsonString(h.mime)
        append('}')
    }

    fun parseHeader(text: String): ClipHeader {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw ClipRejected("header is not JSON", it) }
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ClipRejected("header has no $k")
        val v = (o["v"] as? JsonPrimitive)?.longOrNull ?: throw ClipRejected("header has no v")
        val createdAt = (o["createdAt"] as? JsonPrimitive)?.longOrNull
            ?: throw ClipRejected("header has no createdAt")
        return ClipHeader(
            id = str("id"), origin = str("origin"), name = str("name"),
            createdAt = createdAt, mime = str("mime"), v = v.toInt()
        )
    }

    // ---- sealed clip ----

    fun aad(id: String): ByteArray = (CLIP_AAD_PREFIX + id).toByteArray(Charsets.UTF_8)

    /** `nonce || AES-256-GCM(K, nonce, u32be(len header) || header || body, aad)`. */
    fun seal(key: ByteArray, header: ClipHeader, body: ByteArray, nonce: ByteArray = newNonce()): ByteArray {
        val headerBytes = headerJson(header).toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(4 + headerBytes.size + body.size)
            .putInt(headerBytes.size).put(headerBytes).put(body).array()
        return nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, plain, aad(header.id))
    }

    /**
     * Decrypts [sealed] under [key] with the aad rebuilt from the TRANSPORT's [id],
     * and rejects it unless the header inside names the same id. Successful
     * decryption is the only authentication there is.
     */
    fun open(key: ByteArray, id: String, sealed: ByteArray): OpenedClip {
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8 + 4) throw ClipRejected("too short")
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val plain = try {
            gcm(Cipher.DECRYPT_MODE, key, nonce, sealed.copyOfRange(NONCE_BYTES, sealed.size), aad(id))
        } catch (e: AEADBadTagException) {
            throw ClipRejected("does not decrypt", e)
        } catch (e: java.security.GeneralSecurityException) {
            throw ClipRejected("does not decrypt", e)
        }
        val buf = ByteBuffer.wrap(plain)
        val headerLen = buf.int
        if (headerLen < 0 || headerLen > plain.size - 4) throw ClipRejected("bad header length")
        val header = parseHeader(String(plain, 4, headerLen, Charsets.UTF_8))
        if (header.id != id) throw ClipRejected("header id differs from transport id")
        if (header.v != VERSION) throw ClipRejected("unsupported version ${header.v}")
        if (header.mime !in SUPPORTED_MIMES) throw ClipRejected("unsupported mime ${header.mime}")
        return OpenedClip(header, plain.copyOfRange(4 + headerLen, plain.size))
    }

    // ---- chunking (cloud) ----

    fun chunk(sealed: ByteArray, size: Int = CHUNK_BYTES): List<ByteArray> {
        require(size > 0)
        if (sealed.isEmpty()) return listOf(ByteArray(0))
        return (sealed.indices step size).map { sealed.copyOfRange(it, minOf(it + size, sealed.size)) }
    }

    fun join(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size.toLong() }
        require(total <= MAX_IMAGE_BYTES + 64 * 1024) { "joined clip too large: $total" }
        val out = ByteArray(total.toInt())
        var at = 0
        chunks.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    /** Over the cloud, an image body larger than 2 MiB is re-encoded as JPEG q90 first. */
    fun needsCloudReencode(mime: String, bodySize: Int): Boolean =
        mime.startsWith("image/") && bodySize > CLOUD_REENCODE_ABOVE_BYTES

    // ---- hash (dedupe) ----

    /** SHA-256 over the UTF-8 mime followed by the body: what "identical clip" means. */
    fun hash(mime: String, body: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(mime.toByteArray(Charsets.UTF_8))
        md.update(body)
        return b64url(md.digest())
    }

    // ---- pairing ----

    fun sealPairing(secret: ByteArray, plain: ByteArray, nonce: ByteArray = newNonce()): ByteArray =
        nonce + gcm(Cipher.ENCRYPT_MODE, secret, nonce, plain, PAIR_AAD.toByteArray(Charsets.UTF_8))

    fun openPairing(secret: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) throw ClipRejected("too short")
        return try {
            gcm(
                Cipher.DECRYPT_MODE, secret, sealed.copyOfRange(0, NONCE_BYTES),
                sealed.copyOfRange(NONCE_BYTES, sealed.size), PAIR_AAD.toByteArray(Charsets.UTF_8)
            )
        } catch (e: java.security.GeneralSecurityException) {
            throw ClipRejected("pairing reply does not decrypt", e)
        }
    }

    fun pairRequestJson(request: PairRequest): String = json.encodeToString(PairRequest.serializer(), request)

    fun parsePairResponse(text: String): PairResponse =
        runCatching { json.decodeFromString(PairResponse.serializer(), text) }
            .getOrElse { throw ClipRejected("pairing reply is not understood", it) }

    /** Parses `wpclip1:` + base64url(JSON `{"a":[...],"p":…,"s":"…","n":"…"}`); null if it is not one. */
    fun parseQr(text: String): PairOffer? {
        val t = text.trim()
        if (!t.startsWith(QR_PREFIX)) return null
        return runCatching {
            val o: JsonObject = json.parseToJsonElement(
                String(fromB64url(t.removePrefix(QR_PREFIX)), Charsets.UTF_8)
            ).jsonObject
            val addrs = o.getValue("a").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            val secret = fromB64url(o.getValue("s").jsonPrimitive.content)
            require(secret.size == KEY_BYTES) { "secret is not 32 bytes" }
            require(addrs.isNotEmpty()) { "no addresses" }
            PairOffer(
                addrs = addrs,
                port = o.getValue("p").jsonPrimitive.int,
                secret = secret,
                name = o["n"]?.jsonPrimitive?.contentOrNull ?: "Computer"
            )
        }.getOrNull()
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    private fun StringBuilder.appendJsonString(s: String): StringBuilder {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
        return this
    }
}
