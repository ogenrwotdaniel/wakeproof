package com.ogenrwotdaniel.wakeproof.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRulesTest {

    private val now = 1_762_200_000_000L
    private fun header(origin: String = "laptop", id: String = "a", createdAt: Long = now) =
        ClipHeader(id, origin, "Ubuntu laptop", createdAt, ClipCodec.MIME_TEXT)

    // ---- never-send ----

    @Test
    fun `sensitive clips are never sent`() {
        assertEquals(SendDecision.SENSITIVE, ClipRules.sendDecision("text/plain", "pw".toByteArray(), true, null))
    }

    @Test
    fun `empty and whitespace text is never sent`() {
        assertEquals(SendDecision.EMPTY, ClipRules.sendDecision("text/plain", ByteArray(0), false, null))
        assertEquals(SendDecision.EMPTY, ClipRules.sendDecision("text/plain", " \n\t ".toByteArray(), false, null))
        assertEquals(SendDecision.EMPTY, ClipRules.sendDecision("image/png", ByteArray(0), false, null))
    }

    @Test
    fun `identical to the last sent or received clip is not sent`() {
        val body = "hello".toByteArray()
        val last = ClipCodec.hash("text/plain", body)
        assertEquals(SendDecision.DUPLICATE, ClipRules.sendDecision("text/plain", body, false, last))
        assertEquals(SendDecision.SEND, ClipRules.sendDecision("text/plain", "hello!".toByteArray(), false, last))
    }

    @Test
    fun `size limits and unsupported mimes`() {
        assertEquals(
            SendDecision.TOO_BIG,
            ClipRules.sendDecision("text/plain", ByteArray(ClipCodec.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() }, false, null)
        )
        assertEquals(SendDecision.SEND, ClipRules.sendDecision("image/png", ByteArray(ClipCodec.MAX_TEXT_BYTES + 1), false, null))
        assertEquals(SendDecision.TOO_BIG, ClipRules.sendDecision("image/jpeg", ByteArray(ClipCodec.MAX_IMAGE_BYTES + 1), false, null))
        assertEquals(SendDecision.UNSUPPORTED, ClipRules.sendDecision("image/webp", ByteArray(3), false, null))
    }

    // ---- receiving ----

    @Test
    fun `own origin is ignored`() {
        assertEquals(ReceiveDecision.OWN_ORIGIN, ClipRules.receiveDecision(header(origin = "me"), "me", emptyList(), now))
    }

    @Test
    fun `an id already seen is ignored`() {
        assertEquals(ReceiveDecision.DUPLICATE, ClipRules.receiveDecision(header(id = "x"), "me", listOf("x"), now))
    }

    @Test
    fun `older than ten minutes is stale, ten minutes exactly is not`() {
        val tenMin = ClipRules.STALE_AFTER_MILLIS
        assertEquals(ReceiveDecision.STALE, ClipRules.receiveDecision(header(createdAt = now - tenMin - 1), "me", emptyList(), now))
        assertEquals(ReceiveDecision.ACCEPT, ClipRules.receiveDecision(header(createdAt = now - tenMin), "me", emptyList(), now))
        assertEquals(ReceiveDecision.ACCEPT, ClipRules.receiveDecision(header(createdAt = now + 60_000), "me", emptyList(), now))
    }

    @Test
    fun `remember keeps the newest hundred ids without repeats`() {
        var ids = emptyList<String>()
        repeat(150) { ids = ClipRules.remember(ids, "id$it") }
        assertEquals(100, ids.size)
        assertEquals("id50", ids.first())
        assertEquals("id149", ids.last())
        ids = ClipRules.remember(ids, "id60")
        assertEquals(100, ids.size)
        assertEquals("id60", ids.last())
    }

    // ---- cloud skip ----

    @Test
    fun `cloud skipped only when every fresh presence peer acked`() {
        val presence = mapOf("laptop" to now - 1000, "tablet" to now - 2000)
        assertTrue(ClipRules.cloudSkippable(presence, setOf("laptop", "tablet"), now))
        assertFalse(ClipRules.cloudSkippable(presence, setOf("laptop"), now))
    }

    @Test
    fun `a peer silent for over a week does not hold the cloud open`() {
        val presence = mapOf("laptop" to now - 1000, "old" to now - ClipRules.PRESENCE_FRESH_MILLIS - 1)
        assertTrue(ClipRules.cloudSkippable(presence, setOf("laptop"), now))
    }

    @Test
    fun `with no fresh presence peer the cloud is always used`() {
        assertFalse(ClipRules.cloudSkippable(emptyMap(), emptySet(), now))
        assertFalse(ClipRules.cloudSkippable(emptyMap(), setOf("laptop"), now))
        val stale = mapOf("old" to now - ClipRules.PRESENCE_FRESH_MILLIS - 1)
        assertFalse(ClipRules.cloudSkippable(stale, setOf("old"), now))
    }

    // ---- housekeeping ----

    @Test
    fun `housekeeping drops expired clips and all beyond the ten newest`() {
        val own = (0 until 13).map { OwnCloudClip("c$it", createdAt = now - it * 1000L, expiresAt = now + 3_600_000, chunks = 1) } +
            OwnCloudClip("expired", createdAt = now + 5000, expiresAt = now - 1, chunks = 2)
        val doomed = ClipRules.clipsToDelete(own, now).toSet()
        // "expired" is newest by createdAt, so it displaces c9 from the top ten.
        assertEquals(setOf("expired", "c9", "c10", "c11", "c12"), doomed)
    }
}
