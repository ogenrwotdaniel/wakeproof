package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.remote.AiProvider
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.KimiApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class KimiRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: KimiRepository

    private val fakeKeyStore = object : ApiKeyStore {
        var stored: String? = "test-key"
        var storedProvider: AiProvider = AiProvider.KIMI
        override fun getKey(): String? = stored
        override fun setKey(value: String) { stored = value }
        override fun clear() { stored = null }
        override fun getProvider(): AiProvider = storedProvider
        override fun setProvider(provider: AiProvider) { storedProvider = provider }
    }

    private val passthroughCompressor = object : ImageCompressor {
        override fun compressToJpeg(imageBytes: ByteArray, maxDimension: Int, quality: Int) =
            imageBytes
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KimiApi::class.java)
        repository = KimiRepository(api, fakeKeyStore, passthroughCompressor)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Wraps [content] as the assistant message of a chat completion response. */
    private fun chatBody(content: String): String {
        val escaped = JsonPrimitive(content).toString()
        return """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":$escaped}}]}"""
    }

    /** A clean pair: no printed scale, bottle went from 90 % to 50 % full. */
    private val goodVerdict = """
        {"same_container": true, "level_readable": true, "before_fill": 0.9,
         "after_fill": 0.5, "scale_ml_readable": false, "before_level_ml": 0,
         "after_level_ml": 0, "refilled": false, "reason": "ok"}
    """.trimIndent()

    @Test
    fun `verifyWater credits the fill drop against the declared capacity`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(goodVerdict)))
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750)
        assertTrue(result is KimiResult.Success)
        val verdict = (result as KimiResult.Success).value
        assertTrue(verdict.accepted)
        // 0.4 of 750 ml = 300 ml.
        assertEquals(300, verdict.consumedMl)
    }

    @Test
    fun `verifyWater strips markdown fences`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(chatBody("```json\n$goodVerdict\n```"))
        )
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750)
        assertTrue((result as KimiResult.Success).value.accepted)
    }

    @Test
    fun `a partial gulp is accepted and credited, not rejected`() = runTest {
        // 10 % of a 750 ml bottle = 75 ml: nowhere near a 500 ml target, but real.
        val sip = goodVerdict.replace("\"after_fill\": 0.5", "\"after_fill\": 0.8")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(sip)))
        val verdict = (repository.verifyWater(
            byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750
        ) as KimiResult.Success).value
        assertTrue(verdict.accepted)
        assertEquals(70, verdict.consumedMl) // floored to 10 ml
    }

    @Test
    fun `noise-level differences are not a drink`() = runTest {
        // 2 % of the bottle is within reading error.
        val noise = goodVerdict.replace("\"after_fill\": 0.5", "\"after_fill\": 0.88")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(noise)))
        val verdict = (repository.verifyWater(
            byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750
        ) as KimiResult.Success).value
        assertFalse(verdict.accepted)
    }

    @Test
    fun `an unreadable water line is rejected however big the apparent drop`() = runTest {
        val unreadable = goodVerdict.replace("\"level_readable\": true", "\"level_readable\": false")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(unreadable)))
        val verdict = (repository.verifyWater(
            byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750
        ) as KimiResult.Success).value
        assertFalse(verdict.accepted)
    }

    @Test
    fun `a different bottle or a refill is rejected`() = runTest {
        val swapped = goodVerdict.replace("\"same_container\": true", "\"same_container\": false")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(swapped)))
        assertFalse(
            (repository.verifyWater(
                byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750
            ) as KimiResult.Success).value.accepted
        )

        val refilled = goodVerdict.replace("\"refilled\": false", "\"refilled\": true")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(refilled)))
        assertFalse(
            (repository.verifyWater(
                byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750
            ) as KimiResult.Success).value.accepted
        )
    }

    @Test
    fun `legible ml marks win over the fill estimate`() = runTest {
        val scaled = goodVerdict
            .replace("\"scale_ml_readable\": false", "\"scale_ml_readable\": true")
            .replace("\"before_level_ml\": 0", "\"before_level_ml\": 900")
            .replace("\"after_level_ml\": 0", "\"after_level_ml\": 400")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(scaled)))
        val verdict = (repository.verifyWater(
            byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 1000
        ) as KimiResult.Success).value
        assertTrue(verdict.accepted)
        assertEquals(500, verdict.consumedMl)
    }

    @Test
    fun `credit never exceeds the bottle capacity`() = runTest {
        val scaled = goodVerdict
            .replace("\"scale_ml_readable\": false", "\"scale_ml_readable\": true")
            .replace("\"before_level_ml\": 0", "\"before_level_ml\": 5000")
            .replace("\"after_level_ml\": 0", "\"after_level_ml\": 100")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(scaled)))
        val verdict = (repository.verifyWater(
            byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750
        ) as KimiResult.Success).value
        assertEquals(750, verdict.consumedMl)
    }

    @Test
    fun `malformed model output becomes ApiError not a crash`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(chatBody("Sorry, I cannot verify this."))
        )
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750)
        assertTrue(result is KimiResult.ApiError)
    }

    @Test
    fun `missing key short-circuits without a network call`() = runTest {
        fakeKeyStore.stored = null
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750)
        assertEquals(KimiResult.NoKey, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `retries once on 500 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(goodVerdict)))
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750)
        assertTrue(result is KimiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `client error does not retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2), bottleCapacityMl = 750)
        assertTrue(result is KimiResult.ApiError)
        assertEquals(401, (result as KimiResult.ApiError).code)
        assertEquals(1, server.requestCount)
    }
}
