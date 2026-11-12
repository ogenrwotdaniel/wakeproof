package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.GEMINI_MODEL
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.KimiApi
import com.ogenrwotdaniel.wakeproof.data.remote.KimiChatRequest
import com.ogenrwotdaniel.wakeproof.data.remote.KimiMessage
import com.ogenrwotdaniel.wakeproof.data.remote.KimiResponseFormat
import com.ogenrwotdaniel.wakeproof.data.remote.imagesWithTextContent
import com.ogenrwotdaniel.wakeproof.data.remote.textContent
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Outcome of a Gemini API call; UI decides fallbacks per case. */
sealed interface KimiResult<out T> {
    data class Success<T>(val value: T) : KimiResult<T>
    data class ApiError(val code: Int?, val message: String) : KimiResult<Nothing>
    data object NetworkError : KimiResult<Nothing>
    data object NoKey : KimiResult<Nothing>
}

/** One AI-drafted flashcard, reviewed by the user before saving. */
@Serializable
data class FlashcardDraft(val front: String = "", val back: String = "")

@Serializable
private data class FlashcardEnvelope(val cards: List<FlashcardDraft> = emptyList())

/**
 * Gemini API calls (`gemini-3.8-flash`): flashcard drafting and the Pomodoro study
 * chat. One retry with backoff on
 * 429/5xx; malformed responses become [KimiResult.ApiError], never a crash.
 *
 * Motivational lines and the weekly report live on-device — see
 * [com.ogenrwotdaniel.wakeproof.verify.OfflineMotivation] and
 * [WeeklyReportGenerator]. Neither needs a key, a network, or a round trip.
 */
@Singleton
class KimiRepository @Inject constructor(
    private val api: KimiApi,
    private val keyStore: ApiKeyStore,
    private val imageCompressor: ImageCompressor
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Multi-turn study chat for the Pomodoro assistant. Roles: "user"/"assistant". */
    suspend fun studyChat(history: List<Pair<String, String>>): KimiResult<String> {
        val request = KimiChatRequest(
            model = GEMINI_MODEL,
            maxTokens = 4096,
            reasoningEffort = REASONING_LOW,
            messages = listOf(
                KimiMessage(
                    "system",
                    textContent(
                        "You are a concise study coach inside a Pomodoro timer app. Explain " +
                            "concepts, quiz the user on request, keep answers short."
                    )
                )
            ) + history.map { (role, text) -> KimiMessage(role, textContent(text)) }
        )
        return call(request) { it.trim() }
    }

    /**
     * Drafts flashcards from pasted [sourceText] and/or a [notesPhoto] of
     * handwritten/printed notes. Returns 5–20 front/back pairs the UI lets the
     * user prune before saving.
     */
    suspend fun generateFlashcards(
        sourceText: String?,
        notesPhoto: ByteArray?
    ): KimiResult<List<FlashcardDraft>> {
        val instruction = "Create study flashcards from these notes." +
            (sourceText?.takeIf { it.isNotBlank() }?.let { "\n\nNOTES:\n$it" } ?: "")
        val userContent = if (notesPhoto != null) {
            val encoded = Base64.getEncoder().encodeToString(
                imageCompressor.compressToJpeg(notesPhoto, MAX_IMAGE_DIMENSION, JPEG_QUALITY)
            )
            imagesWithTextContent(instruction, listOf(encoded))
        } else {
            textContent(instruction)
        }
        val request = KimiChatRequest(
            model = GEMINI_MODEL,
            maxTokens = 8192,
            reasoningEffort = REASONING_LOW,
            responseFormat = JSON_OBJECT_FORMAT,
            messages = listOf(
                KimiMessage("system", textContent(FLASHCARD_SYSTEM_PROMPT)),
                KimiMessage("user", userContent)
            )
        )
        return call(request) { content ->
            val cleaned = stripMarkdownFences(content)
            val cards = if (cleaned.startsWith("[")) {
                json.decodeFromString<List<FlashcardDraft>>(cleaned)
            } else {
                json.decodeFromString<FlashcardEnvelope>(cleaned).cards
            }
            cards.filter { it.front.isNotBlank() && it.back.isNotBlank() }.take(30)
        }
    }

    /**
     * Cheapest possible round trip to validate the stored Gemini key. Capped at
     * low reasoning (the minimum supported on `gemini-3.8-flash`) and 1 output
     * token; HTTP 200 proves the key and model work.
     */
    suspend fun testConnection(): KimiResult<Unit> {
        val request = KimiChatRequest(
            model = GEMINI_MODEL,
            maxTokens = 1,
            reasoningEffort = REASONING_LOW,
            messages = listOf(KimiMessage("user", textContent("ping")))
        )
        return when (val result = call(request, allowEmpty = true) { }) {
            is KimiResult.Success -> KimiResult.Success(Unit)
            is KimiResult.ApiError -> result
            KimiResult.NetworkError -> KimiResult.NetworkError
            KimiResult.NoKey -> KimiResult.NoKey
        }
    }

    private suspend fun <T> call(
        request: KimiChatRequest,
        allowEmpty: Boolean = false,
        transform: (String) -> T
    ): KimiResult<T> {
        if (!keyStore.hasKey()) return KimiResult.NoKey
        var lastFailure: KimiResult<Nothing> = KimiResult.NetworkError
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = api.chatCompletions(request)
                if (response.isSuccessful) {
                    val content = response.body()?.choices?.firstOrNull()?.message?.content
                        ?: if (allowEmpty) "" else return KimiResult.ApiError(null, "Empty response from model")
                    return try {
                        KimiResult.Success(transform(content))
                    } catch (e: Exception) {
                        KimiResult.ApiError(null, "Unparseable model response: ${e.message}")
                    }
                }
                val code = response.code()
                val failure = KimiResult.ApiError(
                    code,
                    response.errorBody()?.string()?.take(200) ?: "HTTP $code"
                )
                if (code != 429 && code < 500) return failure
                lastFailure = failure
            } catch (e: IOException) {
                lastFailure = KimiResult.NetworkError
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_BACKOFF_MS * (attempt + 1))
        }
        return lastFailure
    }

    private fun stripMarkdownFences(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val RETRY_BACKOFF_MS = 1_500L
        const val MAX_IMAGE_DIMENSION = 1024
        const val JPEG_QUALITY = 80

        // gemini-3.8-flash accepts "low", "medium", "high" ("minimal" returns HTTP 400).
        const val REASONING_LOW = "low"
        val JSON_OBJECT_FORMAT = KimiResponseFormat("json_object")

        val FLASHCARD_SYSTEM_PROMPT = """
            You turn study notes (text or a photo of notes) into concise flashcards for
            spaced repetition. Rules:
            - Each card tests ONE fact, definition, formula or concept.
            - front: a clear question or prompt. back: the short answer (1-3 lines).
            - Prefer "why/how" and formula cards over trivia for STEM material.
            - Produce between 5 and 20 cards depending on how much material there is.
            - Write math in plain text (e.g. "integral of x^2 dx = x^3/3 + C").
            Respond with ONLY this JSON object, no markdown, no extra text:
            {"cards": [{"front": "string", "back": "string"}]}
        """.trimIndent()
    }
}
