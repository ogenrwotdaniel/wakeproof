package com.ogenrwotdaniel.wakeproof.clip

/** Why a local clipboard change is, or is not, sent. */
enum class SendDecision { SEND, SENSITIVE, EMPTY, UNSUPPORTED, TOO_BIG, DUPLICATE }

/** What to do with a clip that decrypted. */
enum class ReceiveDecision { ACCEPT, OWN_ORIGIN, DUPLICATE, STALE }

/**
 * The never-send and receiving rules of `docs/CLIPBOARD.md`, as pure functions so
 * they are unit-tested rather than reasoned about.
 */
object ClipRules {
    /** A clip older than this on arrival is backlog, not today's clipboard. */
    const val STALE_AFTER_MILLIS = 10 * 60 * 1000L
    /** How many received ids are remembered for dedupe. */
    const val REMEMBERED_IDS = 100
    /** A peer whose presence is older than this no longer counts for the cloud-skip rule. */
    const val PRESENCE_FRESH_MILLIS = 7 * 24 * 60 * 60 * 1000L

    /**
     * Never sent: a clip the source marked sensitive, empty/whitespace text, a mime
     * the protocol does not carry, one over the size limit, or one identical (by
     * [ClipCodec.hash]) to the last clip this device sent or received.
     */
    fun sendDecision(
        mime: String,
        body: ByteArray,
        sensitive: Boolean,
        lastHash: String?
    ): SendDecision = when {
        sensitive -> SendDecision.SENSITIVE
        mime !in ClipCodec.SUPPORTED_MIMES -> SendDecision.UNSUPPORTED
        body.isEmpty() -> SendDecision.EMPTY
        mime == ClipCodec.MIME_TEXT && String(body, Charsets.UTF_8).isBlank() -> SendDecision.EMPTY
        mime == ClipCodec.MIME_TEXT && body.size > ClipCodec.MAX_TEXT_BYTES -> SendDecision.TOO_BIG
        mime != ClipCodec.MIME_TEXT && body.size > ClipCodec.MAX_IMAGE_BYTES -> SendDecision.TOO_BIG
        lastHash != null && ClipCodec.hash(mime, body) == lastHash -> SendDecision.DUPLICATE
        else -> SendDecision.SEND
    }

    /**
     * Receiving: ignore our own origin, an id already seen, and anything created more
     * than [STALE_AFTER_MILLIS] before [now]. A clip from the future (a peer's clock
     * ahead of ours) is accepted — refusing it would silently lose clips.
     */
    fun receiveDecision(
        header: ClipHeader,
        ownInstallId: String,
        seenIds: Collection<String>,
        now: Long
    ): ReceiveDecision = when {
        header.origin == ownInstallId -> ReceiveDecision.OWN_ORIGIN
        header.id in seenIds -> ReceiveDecision.DUPLICATE
        now - header.createdAt > STALE_AFTER_MILLIS -> ReceiveDecision.STALE
        else -> ReceiveDecision.ACCEPT
    }

    /** Appends [id] to the remembered list, keeping only the newest [REMEMBERED_IDS]. */
    fun remember(ids: List<String>, id: String): List<String> =
        (ids.filter { it != id } + id).takeLast(REMEMBERED_IDS)

    /**
     * Whether the cloud copy can be skipped: every peer with a presence doc updated
     * in the last 7 days acknowledged over LAN. With NO fresh presence peer the
     * cloud is always used (agreed with the laptop side): a LAN ack from a peer
     * that has no presence doc says nothing about the ones that might exist.
     */
    fun cloudSkippable(
        presence: Map<String, Long>,
        acked: Set<String>,
        now: Long
    ): Boolean {
        val fresh = presence.filterValues { now - it <= PRESENCE_FRESH_MILLIS }.keys
        if (fresh.isEmpty()) return false
        return acked.containsAll(fresh)
    }

    /**
     * Housekeeping: of this device's own cloud clips (id to createdAt/expiresAt),
     * the ids to delete — expired ones and all beyond the [keep] newest.
     */
    fun clipsToDelete(own: List<OwnCloudClip>, now: Long, keep: Int = 10): List<String> {
        val expired = own.filter { it.expiresAt < now }.map { it.id }.toSet()
        val beyond = own.sortedByDescending { it.createdAt }.drop(keep).map { it.id }
        return (expired + beyond).distinct()
    }
}

/** One of this device's own `clip_` headers in the cloud, for [ClipRules.clipsToDelete]. */
data class OwnCloudClip(val id: String, val createdAt: Long, val expiresAt: Long, val chunks: Int)
