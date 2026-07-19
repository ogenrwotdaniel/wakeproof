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

    private val goodVerdict = """
        {"same_container": true, "capacity_estimate_ml": 500, "photo1_full": true,
         "photo2_empty": true, "verified": true, "reason": "ok"}
    """.trimIndent()

    @Test
    fun `verifyWater parses a clean verdict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(goodVerdict)))
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2))
        assertTrue(result is KimiResult.Success)
        assertTrue((result as KimiResult.Success).value.verified)
    }

    @Test
    fun `verifyWater strips markdown fences`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(chatBody("```json\n$goodVerdict\n```"))
        )
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2))
        assertTrue((result as KimiResult.Success).value.verified)
    }

    @Test
    fun `verifyWater enforces capacity rule locally even if model says verified`() = runTest {
        val tinyCup = goodVerdict.replace("\"capacity_estimate_ml\": 500", "\"capacity_estimate_ml\": 200")
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(tinyCup)))
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2))
        assertFalse((result as KimiResult.Success).value.verified)
    }

    @Test
    fun `malformed model output becomes ApiError not a crash`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(chatBody("Sorry, I cannot verify this."))
        )
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2))
        assertTrue(result is KimiResult.ApiError)
    }

    @Test
    fun `missing key short-circuits without a network call`() = runTest {
        fakeKeyStore.stored = null
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2))
        assertEquals(KimiResult.NoKey, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `retries once on 500 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(goodVerdict)))
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2))
        assertTrue(result is KimiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `client error does not retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val result = repository.verifyWater(byteArrayOf(1), byteArrayOf(2))
        assertTrue(result is KimiResult.ApiError)
        assertEquals(401, (result as KimiResult.ApiError).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `motivationalMessage returns trimmed text and null on failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody("  Go get it.  ")))
        assertEquals("Go get it.", repository.motivationalMessage("streak 5"))

        server.enqueue(MockResponse().setResponseCode(401).setBody("no"))
        assertEquals(null, repository.motivationalMessage("streak 5"))
    }
}
