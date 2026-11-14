package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.GEMINI_MODEL
import com.ogenrwotdaniel.wakeproof.data.remote.GeminiApi
import com.ogenrwotdaniel.wakeproof.data.remote.GeminiChatRequest
import com.ogenrwotdaniel.wakeproof.data.remote.GeminiMessage
import com.ogenrwotdaniel.wakeproof.data.remote.GeminiResponseFormat
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.imagesWithTextContent
import com.ogenrwotdaniel.wakeproof.data.remote.textContent
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Outcome of a Google Gemini API call; UI decides fallbacks per case. */
sealed interface GeminiResult<out T> {
    data class Success<T>(val value: T) : GeminiResult<T>
    data class ApiError(val code: Int?, val message: String) : GeminiResult<Nothing>
    data object NetworkError : GeminiResult<Nothing>
    data object NoKey : GeminiResult<Nothing>
}

typealias KimiResult<T> = GeminiResult<T>

/** One AI-drafted flashcard, reviewed by the user before saving. */
@Serializable
data class FlashcardDraft(val front: String = "", val back: String = "")

@Serializable
private data class FlashcardEnvelope(val cards: List<FlashcardDraft> = emptyList())

/**
 * Google Gemini API calls (`gemini-3.8-flash`): researcher-grade flashcard
 * drafting and the Pomodoro study chat (Coach & Socratic Referee modes). One
 * retry with backoff on 429/5xx; malformed responses become
 * [GeminiResult.ApiError], never a crash.
 *
 * Motivational lines and the weekly report live on-device — see
 * [com.ogenrwotdaniel.wakeproof.verify.OfflineMotivation] and
 * [WeeklyReportGenerator]. Neither needs a key, a network, or a round trip.
 */
@Singleton
class GeminiRepository @Inject constructor(
    private val api: GeminiApi,
    private val keyStore: ApiKeyStore,
    private val imageCompressor: ImageCompressor
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Multi-turn study chat for the Pomodoro assistant. Roles: "user"/"assistant".
     * When [socratic] is true, acts as an Euler-style Socratic Referee that probes
     * assumptions, edge cases, and proof steps rather than handing out direct answers.
     */
    suspend fun studyChat(
        history: List<Pair<String, String>>,
        socratic: Boolean = false
    ): GeminiResult<String> {
        val systemPrompt = if (socratic) SOCRATIC_SYSTEM_PROMPT else COACH_SYSTEM_PROMPT
        val request = GeminiChatRequest(
            model = GEMINI_MODEL,
            maxTokens = 4096,
            reasoningEffort = REASONING_LOW,
            messages = listOf(
                GeminiMessage("system", textContent(systemPrompt))
            ) + history.map { (role, text) -> GeminiMessage(role, textContent(text)) }
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
    ): GeminiResult<List<FlashcardDraft>> {
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
        val request = GeminiChatRequest(
            model = GEMINI_MODEL,
            maxTokens = 8192,
            reasoningEffort = REASONING_LOW,
            responseFormat = JSON_OBJECT_FORMAT,
            messages = listOf(
                GeminiMessage("system", textContent(FLASHCARD_SYSTEM_PROMPT)),
                GeminiMessage("user", userContent)
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
    suspend fun testConnection(): GeminiResult<Unit> {
        val request = GeminiChatRequest(
            model = GEMINI_MODEL,
            maxTokens = 1,
            reasoningEffort = REASONING_LOW,
            messages = listOf(GeminiMessage("user", textContent("ping")))
        )
        return when (val result = call(request, allowEmpty = true) { }) {
            is GeminiResult.Success -> GeminiResult.Success(Unit)
            is GeminiResult.ApiError -> result
            GeminiResult.NetworkError -> GeminiResult.NetworkError
            GeminiResult.NoKey -> GeminiResult.NoKey
        }
    }

    private suspend fun <T> call(
        request: GeminiChatRequest,
        allowEmpty: Boolean = false,
        transform: (String) -> T
    ): GeminiResult<T> {
        if (!keyStore.hasKey()) return GeminiResult.NoKey
        var lastFailure: GeminiResult<Nothing> = GeminiResult.NetworkError
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = api.chatCompletions(request)
                if (response.isSuccessful) {
                    val content = response.body()?.choices?.firstOrNull()?.message?.content
                        ?: if (allowEmpty) "" else return GeminiResult.ApiError(null, "Empty response from model")
                    return try {
                        GeminiResult.Success(transform(content))
                    } catch (e: Exception) {
                        GeminiResult.ApiError(null, "Unparseable model response: ${e.message}")
                    }
                }
                val code = response.code()
                val failure = GeminiResult.ApiError(
                    code,
                    response.errorBody()?.string()?.take(200) ?: "HTTP $code"
                )
                if (code != 429 && code < 500) return failure
                lastFailure = failure
            } catch (e: IOException) {
                lastFailure = GeminiResult.NetworkError
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
        val JSON_OBJECT_FORMAT = GeminiResponseFormat("json_object")

        const val COACH_SYSTEM_PROMPT =
            "You are a concise study and research coach inside Euler's Lab (specializing in " +
                "Mechanical Engineering, Deep Learning, Graphene & Materials Science, Computer Vision, " +
                "and Mathematics). Explain concepts, quiz the user on request, and keep answers short."

        val SOCRATIC_SYSTEM_PROMPT = """
            You are an Euler-caliber mathematical, mechanical engineering, materials/graphene, computer vision, and deep learning referee inside Euler's Lab.
            Rules:
            - Do NOT hand over complete solutions immediately. Ask the user to state their definitions, governing equations, physical/geometric assumptions, or proof idea first.
            - Challenge unstated assumptions, boundary cases (n -> 0, n -> infinity, singular Jacobians/Hessians, continuum vs. atomistic limits, degenerate camera poses), and necessary vs. sufficient conditions.
            - If the user makes a loose claim, offer a concrete counterexample or ask what breaks when a hypothesis or physical constraint is dropped.
            - Keep every response sharp, rigorous, and under 4 sentences.
        """.trimIndent()

        val FLASHCARD_SYSTEM_PROMPT = """
            You turn study notes, papers, or derivations (text or a photo of handwritten notes across Mechanical Engineering, Deep Learning, Graphene/Materials, Computer Vision, and Mathematics) into
            researcher-grade flashcards for spaced repetition in Euler's Lab. Rules:
            - Generate cards across four deep-learning archetypes:
              1. Proof Skeleton / Derivation Crux Step ("How do you derive/prove X, and what is the crux step or conservation law?")
              2. Hypothesis & Boundary-Case Audit ("Why is condition/assumption Y required in X? What physical regime or counterexample breaks it if dropped?")
              3. Method Trigger ("When you see problem structure, PDE, lattice defect, or loss landscape Z, what transformation, invariant, or architecture applies?")
              4. Core Definition, Governing Equation, or Theorem
            - Each card tests ONE concept, derivation step, boundary condition, or formula.
            - front: a clear question or prompt. back: the concise, rigorous answer (1-3 lines).
            - Produce between 5 and 20 cards depending on how much material there is.
            - Write math in plain text (e.g. "integral of x^2 dx = x^3/3 + C").
            Respond with ONLY this JSON object, no markdown, no extra text:
            {"cards": [{"front": "string", "back": "string"}]}
        """.trimIndent()
    }
}

typealias KimiRepository = GeminiRepository
