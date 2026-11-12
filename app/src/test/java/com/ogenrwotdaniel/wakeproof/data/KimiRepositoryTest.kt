package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.GEMINI_MODEL
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.KimiApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class KimiRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: KimiRepository
    private val wireJson = Json { ignoreUnknownKeys = true }

    private val fakeKeyStore = object : ApiKeyStore {
        var stored: String? = "test-key"
        override fun getKey(): String? = stored
        override fun setKey(value: String) { stored = value }
        override fun clear() { stored = null }
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

    @Test
    fun `generateFlashcards parses json_object envelope and bare array fallback`() = runTest {
        val envelope = """{"cards":[{"front":"Q1","back":"A1"},{"front":"","back":"skip"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(envelope)))
        val first = repository.generateFlashcards("notes", null) as KimiResult.Success
        assertEquals(listOf(FlashcardDraft("Q1", "A1")), first.value)

        val sent = wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(GEMINI_MODEL, sent["model"]?.jsonPrimitive?.content)
        assertEquals("low", sent["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(
            "json_object",
            sent["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        )

        val bareArray = """[{"front":"Q2","back":"A2"}]"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(bareArray)))
        val second = repository.generateFlashcards("notes", null) as KimiResult.Success
        assertEquals(listOf(FlashcardDraft("Q2", "A2")), second.value)
    }

    @Test
    fun `studyChat caps reasoning at low without json response_format`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody("  Short answer.  ")))
        val reply = repository.studyChat(listOf("user" to "Explain osmosis")) as KimiResult.Success
        assertEquals("Short answer.", reply.value)

        val sent = wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(GEMINI_MODEL, sent["model"]?.jsonPrimitive?.content)
        assertEquals("low", sent["reasoning_effort"]?.jsonPrimitive?.content)
        assertNull(sent["response_format"])
    }

    @Test
    fun `testConnection sends low reasoning and 1 token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"1","choices":[{"index":0,"message":{"role":"assistant"}}]}"""))
        val res = repository.testConnection()
        assertEquals(KimiResult.Success(Unit), res)

        val sent = wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(GEMINI_MODEL, sent["model"]?.jsonPrimitive?.content)
        assertEquals("low", sent["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("1", sent["max_tokens"]?.jsonPrimitive?.content)
    }

    private val goodCards = """{"cards":[{"front":"Q1","back":"A1"}]}"""

    @Test
    fun `markdown fences around the json are stripped`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(chatBody("```json\n$goodCards\n```"))
        )
        val result = repository.generateFlashcards("notes", null)
        assertEquals(listOf(FlashcardDraft("Q1", "A1")), (result as KimiResult.Success).value)
    }

    @Test
    fun `malformed model output becomes ApiError not a crash`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(chatBody("Sorry, I cannot do this."))
        )
        val result = repository.generateFlashcards("notes", null)
        assertTrue(result is KimiResult.ApiError)
    }

    @Test
    fun `missing key short-circuits without a network call`() = runTest {
        fakeKeyStore.stored = null
        val result = repository.generateFlashcards("notes", null)
        assertEquals(KimiResult.NoKey, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `retries once on 500 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatBody(goodCards)))
        val result = repository.generateFlashcards("notes", null)
        assertTrue(result is KimiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `client error does not retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val result = repository.generateFlashcards("notes", null)
        assertTrue(result is KimiResult.ApiError)
        assertEquals(401, (result as KimiResult.ApiError).code)
        assertEquals(1, server.requestCount)
    }
}
