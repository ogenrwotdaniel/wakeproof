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
import com.ogenrwotdaniel.wakeproof.verify.water.WaterVerdictRules
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
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
 * The strict-JSON verdict for one before/after pair of bottle photos.
 *
 * The model's only hard job is the easy one: locate the water line in both shots
 * and say how full the bottle is, as a fraction of its water column. Printed ml
 * marks are used when they happen to be legible ([scaleMlReadable]) but are no
 * longer required — most bottles don't have them, and reading them was where the
 * old check failed. [consumedMl] and [accepted] are always computed locally in
 * [KimiRepository.verifyWater], never taken from the model.
 */
@Serializable
data class WaterVerdict(
    @SerialName("same_container") val sameContainer: Boolean = false,
    /** Is the water line locatable in BOTH photos? The one thing we insist on. */
    @SerialName("level_readable") val levelReadable: Boolean = false,
    /** How full the bottle is before drinking: 0.0 = empty, 1.0 = full to the brim. */
    @SerialName("before_fill") val beforeFill: Double = 0.0,
    @SerialName("after_fill") val afterFill: Double = 0.0,
    /** True only if printed ml marks are legible in both photos. */
    @SerialName("scale_ml_readable") val scaleMlReadable: Boolean = false,
    @SerialName("before_level_ml") val beforeLevelMl: Int = 0,
    @SerialName("after_level_ml") val afterLevelMl: Int = 0,
    /** The bottle was topped up between the shots, so the drop means nothing. */
    val refilled: Boolean = false,
    val reason: String = "",
    /** Locally computed: ml drunk between these two photos. */
    val consumedMl: Int = 0,
    /** Locally computed: this pair is a real, measurable drop on the same bottle. */
    val accepted: Boolean = false
)

/**
 * Gemini API calls (`gemini-3.8-flash`): water verification second-opinion,
 * flashcard drafting and the Pomodoro study chat. One retry with backoff on
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

    /**
     * Compares one before/after pair of photos of the same bottle and reports how
     * much was drunk between them.
     *
     * The verdict is always re-decided locally; the model only supplies
     * observations. A pair is [WaterVerdict.accepted] when it is the same bottle,
     * the water line is readable in both shots, nothing was refilled, and the
     * level fell far enough to be a real gulp rather than reading noise
     * ([WaterVerdictRules.MIN_FILL_DROP] of the bottle, or a legible ml reading).
     */
    suspend fun verifyWater(
        beforePhoto: ByteArray,
        afterPhoto: ByteArray,
        bottleCapacityMl: Int
    ): KimiResult<WaterVerdict> {
        val encoder = Base64.getEncoder()
        val images = listOf(beforePhoto, afterPhoto).map {
            encoder.encodeToString(
                imageCompressor.compressToJpeg(it, WATER_IMAGE_DIMENSION, WATER_JPEG_QUALITY)
            )
        }
        val request = KimiChatRequest(
            model = GEMINI_MODEL,
            reasoningEffort = REASONING_LOW,
            responseFormat = JSON_OBJECT_FORMAT,
            messages = listOf(
                KimiMessage("system", textContent(WATER_SYSTEM_PROMPT)),
                KimiMessage(
                    "user",
                    imagesWithTextContent(
                        "Image 1 is BEFORE drinking, image 2 is AFTER drinking. The bottle holds " +
                            "about $bottleCapacityMl ml when full. Find the water line in each " +
                            "photo and report how full the bottle is. Respond with the JSON only.",
                        images
                    )
                )
            )
        )
        return call(request) { content ->
            // The model supplies observations only; the verdict is recomputed
            // here, from rules the on-device observer shares verbatim.
            WaterVerdictRules.decide(
                json.decodeFromString<WaterVerdict>(stripMarkdownFences(content)),
                bottleCapacityMl
            )
        }
    }

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

        // Water photos keep a larger, sharper frame so the water line — and any
        // printed ml marks — stay legible to the vision model.
        const val WATER_IMAGE_DIMENSION = 1600
        const val WATER_JPEG_QUALITY = 88

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

        val WATER_SYSTEM_PROMPT = """
            You compare two photos of the SAME drinking bottle or glass: image 1 is BEFORE
            drinking, image 2 is AFTER drinking. Your job is to locate the water line (the
            top surface of the liquid) in each photo and say how full the container is.
            Do NOT require a printed millilitre scale — most bottles have none.

            Measure fullness as a fraction of the container's own water column: 0.0 = empty,
            0.5 = half full, 1.0 = filled to the brim. Judge it from the visible geometry
            (bottle height, shoulder, base), not from any label. An opaque or metal bottle,
            a hand covering the water line, heavy glare or motion blur means you cannot see
            the level — say so instead of guessing.

            Report:
            - same_container: are both photos the SAME physical container (shape, colour,
              cap, label, surroundings)? A different bottle = false.
            - level_readable: can you locate the water line in BOTH photos? If either is
              opaque, covered, blurred or too dark = false.
            - before_fill / after_fill: fullness fraction in image 1 and image 2 (0.0-1.0).
            - scale_ml_readable: true ONLY if printed ml marks are legible in BOTH photos
              and you can read the water line against them; otherwise false.
            - before_level_ml / after_level_ml: the readings in ml if scale_ml_readable,
              else 0.
            - refilled: true if the container was topped up between the shots (after_fill
              higher than before_fill, fresh condensation/bubbles at a higher line).
            - reason: one short sentence explaining what you saw.

            Be skeptical: an identical level in both photos, a different container, a photo
            of a screen or of another photo, or a level that went UP must be reported
            honestly — do not flatter the user. Small honest drops are fine to report; the
            app decides whether the amount is enough.

            Respond with ONLY this JSON object, no markdown, no extra text:
            {"same_container": bool, "level_readable": bool, "before_fill": number,
             "after_fill": number, "scale_ml_readable": bool, "before_level_ml": int,
             "after_level_ml": int, "refilled": bool, "reason": "string"}
        """.trimIndent()
    }
}
