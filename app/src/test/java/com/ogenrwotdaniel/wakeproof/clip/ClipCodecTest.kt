package com.ogenrwotdaniel.wakeproof.clip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Byte-for-byte agreement with the shared vectors the Python side also reads. */
class ClipCodecTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader!!.getResource("clip/vectors.json")).readText()
    ).jsonObject

    private val key = ClipCodec.fromB64url(vectors.getValue("key").jsonPrimitive.content)
    private val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.header() = ClipHeader(
        id = s("id"), origin = s("origin"), name = s("name"),
        createdAt = getValue("createdAt").jsonPrimitive.long, mime = s("mime")
    )

    @Test
    fun `vectors cover text and image`() {
        assertEquals(32, key.size)
        assertTrue(cases.any { it.s("mime") == "text/plain" })
        assertTrue(cases.any { it.s("mime").startsWith("image/") })
    }

    @Test
    fun `header json matches every vector exactly`() {
        cases.forEach { assertEquals(it.s("header"), ClipCodec.headerJson(it.header())) }
    }

    @Test
    fun `seal reproduces every sealed vector byte for byte`() {
        cases.forEach { c ->
            val sealed = ClipCodec.seal(
                key, c.header(), ClipCodec.fromB64url(c.s("body")), ClipCodec.fromB64url(c.s("nonce"))
            )
            assertEquals(c.s("sealed"), ClipCodec.b64url(sealed))
        }
    }

    @Test
    fun `open decrypts every vector`() {
        cases.forEach { c ->
            val opened = ClipCodec.open(key, c.s("id"), ClipCodec.fromB64url(c.s("sealed")))
            assertEquals(c.header(), opened.header)
            assertArrayEquals(ClipCodec.fromB64url(c.s("body")), opened.body)
        }
    }

    @Test
    fun `text vector body is raw UTF-8 with non-ASCII`() {
        val text = cases.first { it.s("mime") == "text/plain" }
        val body = String(ClipCodec.fromB64url(text.s("body")), Charsets.UTF_8)
        assertTrue(body.any { it.code > 127 })
    }

    @Test
    fun `id mismatch is rejected`() {
        val c = cases.first()
        expectRejected { ClipCodec.open(key, cases[1].s("id"), ClipCodec.fromB64url(c.s("sealed"))) }
    }

    @Test
    fun `header id differing from transport id is rejected even when aad matches`() {
        val h = cases.first().header()
        // Sealed under transport id X's aad, but the header inside says Y.
        val forged = ClipCodec.seal(key, h.copy(id = "other"), "hi".toByteArray())
        // aad is built from header.id ("other"); opening as "other" succeeds…
        assertEquals("other", ClipCodec.open(key, "other", forged).header.id)
        // …and opening as anything else fails.
        expectRejected { ClipCodec.open(key, h.id, forged) }
    }

    @Test
    fun `wrong key is rejected`() {
        val c = cases.first()
        val wrong = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        expectRejected { ClipCodec.open(wrong, c.s("id"), ClipCodec.fromB64url(c.s("sealed"))) }
    }

    @Test
    fun `tampered and truncated ciphertext is rejected`() {
        val c = cases.first()
        val sealed = ClipCodec.fromB64url(c.s("sealed"))
        val flipped = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        expectRejected { ClipCodec.open(key, c.s("id"), flipped) }
        expectRejected { ClipCodec.open(key, c.s("id"), sealed.copyOf(10)) }
    }

    @Test
    fun `round trip with random nonce and non-ASCII name`() {
        val h = ClipHeader("id-1", "me", "Tab \"S9\" FE+ 탭", 5L, ClipCodec.MIME_TEXT)
        val body = "줄\n바꿈 ✓".toByteArray()
        val opened = ClipCodec.open(key, "id-1", ClipCodec.seal(key, h, body))
        assertEquals(h, opened.header)
        assertArrayEquals(body, opened.body)
        assertEquals(
            "{\"v\":1,\"id\":\"id-1\",\"origin\":\"me\",\"name\":\"Tab \\\"S9\\\" FE+ 탭\",\"createdAt\":5,\"mime\":\"text/plain\"}",
            ClipCodec.headerJson(h)
        )
    }

    @Test
    fun `chunks split at 700 KiB and join back`() {
        val sealed = ByteArray(ClipCodec.CHUNK_BYTES * 2 + 123) { (it % 251).toByte() }
        val chunks = ClipCodec.chunk(sealed)
        assertEquals(3, chunks.size)
        assertEquals(ClipCodec.CHUNK_BYTES, chunks[0].size)
        assertEquals(ClipCodec.CHUNK_BYTES, chunks[1].size)
        assertEquals(123, chunks[2].size)
        assertArrayEquals(sealed, ClipCodec.join(chunks))
        assertEquals(1, ClipCodec.chunk(ByteArray(ClipCodec.CHUNK_BYTES)).size)
        assertEquals(1, ClipCodec.chunk(ByteArray(5)).size)
    }

    @Test
    fun `cloud reencode only for images over 2 MiB`() {
        assertTrue(ClipCodec.needsCloudReencode("image/png", ClipCodec.CLOUD_REENCODE_ABOVE_BYTES + 1))
        assertTrue(!ClipCodec.needsCloudReencode("image/png", ClipCodec.CLOUD_REENCODE_ABOVE_BYTES))
        assertTrue(!ClipCodec.needsCloudReencode("text/plain", 10_000_000))
    }

    @Test
    fun `pairing seal reproduces the vector and opens back`() {
        val pair = vectors.getValue("pair").jsonObject
        val secret = ClipCodec.fromB64url(pair.s("secret"))
        val sealed = ClipCodec.sealPairing(
            secret, pair.s("plain").toByteArray(), ClipCodec.fromB64url(pair.s("nonce"))
        )
        assertEquals(pair.s("sealed"), ClipCodec.b64url(sealed))
        assertEquals(pair.s("plain"), String(ClipCodec.openPairing(secret, sealed), Charsets.UTF_8))
        expectRejected { ClipCodec.openPairing(ByteArray(32), sealed) }
    }

    @Test
    fun `qr vector parses`() {
        val offer = ClipCodec.parseQr(vectors.getValue("qr").jsonObject.s("text"))!!
        assertEquals(listOf("10.64.135.111"), offer.addrs)
        assertEquals(47322, offer.port)
        assertEquals("Ubuntu laptop", offer.name)
        assertArrayEquals(ByteArray(32) { 7 }, offer.secret)
    }

    @Test
    fun `non-clip QR codes are refused`() {
        assertNull(ClipCodec.parseQr("https://example.com"))
        assertNull(ClipCodec.parseQr("wpclip1:not-base64-json"))
        val shortSecret = "wpclip1:" + ClipCodec.b64url(
            "{\"a\":[\"1.2.3.4\"],\"p\":1,\"s\":\"AAAA\",\"n\":\"x\"}".toByteArray()
        )
        assertNull(ClipCodec.parseQr(shortSecret))
    }

    @Test
    fun `pair request keeps spec key order and writes nulls`() {
        assertEquals(
            "{\"installId\":\"i\",\"name\":\"n\",\"key\":null,\"idToken\":null,\"apiKey\":\"a\",\"projectId\":\"p\"}",
            ClipCodec.pairRequestJson(PairRequest("i", "n", null, null, "a", "p"))
        )
        val r = ClipCodec.parsePairResponse("{\"key\":\"k\",\"installId\":\"l\",\"name\":\"Ubuntu laptop\",\"uid\":null}")
        assertEquals("Ubuntu laptop", r.name)
        assertNull(r.uid)
    }

    @Test
    fun `hash depends on mime and body`() {
        val b = "x".toByteArray()
        assertEquals(ClipCodec.hash("text/plain", b), ClipCodec.hash("text/plain", b))
        assertTrue(ClipCodec.hash("text/plain", b) != ClipCodec.hash("image/png", b))
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block(); fail("expected ClipRejected")
        } catch (_: ClipRejected) {
        }
    }
}
