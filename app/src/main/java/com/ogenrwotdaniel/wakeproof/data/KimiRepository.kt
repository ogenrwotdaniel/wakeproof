package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.KimiApi
import com.ogenrwotdaniel.wakeproof.data.remote.KimiChatRequest
import com.ogenrwotdaniel.wakeproof.data.remote.KimiMessage
import com.ogenrwotdaniel.wakeproof.data.remote.imagesWithTextContent
import com.ogenrwotdaniel.wakeproof.data.remote.textContent
import com.ogenrwotdaniel.wakeproof.verify.MotivationSource
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Outcome of a Kimi API call; UI decides fallbacks per case. */
sealed interface KimiResult<out T> {
    data class Success<T>(val value: T) : KimiResult<T>
    data class ApiError(val code: Int?, val message: String) : KimiResult<Nothing>
    data object NetworkError : KimiResult<Nothing>
    data object NoKey : KimiResult<Nothing>
}

/** One AI-drafted flashcard, reviewed by the user before saving. */
@Serializable
data class FlashcardDraft(val front: String = "", val back: String = "")

/**
 * The strict-JSON verdict for the two-photo water check. The bottle carries a
 * printed ml scale; the model reads the water line against it before and after
 * drinking, and [consumedMl] is the drop between the two.
 */
@Serializable
data class WaterVerdict(
    @SerialName("same_container") val sameContainer: Boolean = false,
    @SerialName("scale_visible") val scaleVisible: Boolean = false,
    @SerialName("before_level_ml") val beforeLevelMl: Int = 0,
    @SerialName("after_level_ml") val afterLevelMl: Int = 0,
    @SerialName("consumed_ml") val consumedMl: Int = 0,
    val verified: Boolean = false,
    val reason: String = ""
)

/**
 * All Kimi (Moonshot) calls: water verification, motivational messages, and
 * the Pomodoro study chat. One retry with backoff on 429/5xx; malformed
 * responses become [KimiResult.ApiError], never a crash.
 */
@Singleton
class KimiRepository @Inject constructor(
    private val api: KimiApi,
    private val keyStore: ApiKeyStore,
    private val imageCompressor: ImageCompressor
) : MotivationSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Sends the before/after photos in one request. Verdict is re-computed
     * locally (the model's own `verified` is ignored): same bottle AND a legible
     * ml scale in both AND the level dropped by at least [minConsumedMl] (less a
     * small reading tolerance, since printed marks are coarse). [WaterVerdict.consumedMl]
     * is always recomputed as before − after so downstream logging uses the real amount.
     */
    suspend fun verifyWater(
        beforePhoto: ByteArray,
        afterPhoto: ByteArray,
        minConsumedMl: Int
    ): KimiResult<WaterVerdict> {
        val encoder = Base64.getEncoder()
        val images = listOf(beforePhoto, afterPhoto).map {
            encoder.encodeToString(
                imageCompressor.compressToJpeg(it, WATER_IMAGE_DIMENSION, WATER_JPEG_QUALITY)
            )
        }
        val request = KimiChatRequest(
            model = keyStore.getProvider().model,
            temperature = 0.2,
            messages = listOf(
                KimiMessage("system", textContent(WATER_SYSTEM_PROMPT)),
                KimiMessage(
                    "user",
                    imagesWithTextContent(
                        "Image 1 is BEFORE drinking, image 2 is AFTER drinking. Read the water " +
                            "line against the printed ml scale in each. Respond with the JSON only.",
                        images
                    )
                )
            )
        )
        return call(request) { content ->
            val verdict = json.decodeFromString<WaterVerdict>(stripMarkdownFences(content))
            val consumed = (verdict.beforeLevelMl - verdict.afterLevelMl).coerceAtLeast(0)
            verdict.copy(
                consumedMl = consumed,
                verified = verdict.sameContainer &&
                    verdict.scaleVisible &&
                    verdict.beforeLevelMl > verdict.afterLevelMl &&
                    consumed >= (minConsumedMl - READING_TOLERANCE_ML)
            )
        }
    }

    /** 1–2 encouraging sentences; null on any failure (callers show nothing). */
    override suspend fun motivationalMessage(context: String): String? {
        val request = KimiChatRequest(
            model = keyStore.getProvider().model,
            temperature = 0.7,
            maxTokens = 2048,
            messages = listOf(
                KimiMessage(
                    "system",
                    textContent(
                        "You are a terse, upbeat discipline coach. Reply with 1-2 short " +
                            "sentences of encouragement. No emojis, no preamble."
                    )
                ),
                KimiMessage("user", textContent(context))
            )
        )
        return when (val result = call(request) { it.trim() }) {
            is KimiResult.Success -> result.value.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** Multi-turn study chat for the Pomodoro assistant. Roles: "user"/"assistant". */
    suspend fun studyChat(history: List<Pair<String, String>>): KimiResult<String> {
        val request = KimiChatRequest(
            model = keyStore.getProvider().model,
            temperature = 0.6,
            maxTokens = 4096,
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
            model = keyStore.getProvider().model,
            temperature = 0.4,
            maxTokens = 8192,
            messages = listOf(
                KimiMessage("system", textContent(FLASHCARD_SYSTEM_PROMPT)),
                KimiMessage("user", userContent)
            )
        )
        return call(request) { content ->
            json.decodeFromString<List<FlashcardDraft>>(stripMarkdownFences(content))
                .filter { it.front.isNotBlank() && it.back.isNotBlank() }
                .take(30)
        }
    }

    /** A short coaching write-up of the week from an aggregate stats summary. */
    suspend fun weeklyReport(statsSummary: String): KimiResult<String> {
        val request = KimiChatRequest(
            model = keyStore.getProvider().model,
            temperature = 0.7,
            maxTokens = 2048,
            messages = listOf(
                KimiMessage(
                    "system",
                    textContent(
                        "You are a supportive but honest study coach reviewing a STEM " +
                            "student's week. From the stats given, write a report of at " +
                            "most 130 words with exactly three short sections: " +
                            "'Wins:', 'Watch out:' and 'Next week:' (one concrete, " +
                            "specific suggestion). Plain text, no markdown headers."
                    )
                ),
                KimiMessage("user", textContent(statsSummary))
            )
        )
        return call(request) { it.trim() }
    }

    /**
     * Cheapest possible round trip to validate the stored key. Thinking models
     * (Gemini 3.x, Kimi K2.x) spend the whole 1-token budget on reasoning and
     * return no content — an HTTP 200 alone proves the key and model are valid.
     */
    suspend fun testConnection(): KimiResult<Unit> {
        val request = KimiChatRequest(
            model = keyStore.getProvider().model,
            maxTokens = 1,
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

        // Water photos keep a larger, sharper frame so the small printed ml
        // marks stay legible to the vision model.
        const val WATER_IMAGE_DIMENSION = 1600
        const val WATER_JPEG_QUALITY = 88

        // Printed scales are ~100 ml apart, so allow the reading to fall a little
        // short of the target before rejecting.
        const val READING_TOLERANCE_ML = 50

        val FLASHCARD_SYSTEM_PROMPT = """
            You turn study notes (text or a photo of notes) into concise flashcards for
            spaced repetition. Rules:
            - Each card tests ONE fact, definition, formula or concept.
            - front: a clear question or prompt. back: the short answer (1-3 lines).
            - Prefer "why/how" and formula cards over trivia for STEM material.
            - Produce between 5 and 20 cards depending on how much material there is.
            - Write math in plain text (e.g. "integral of x^2 dx = x^3/3 + C").
            Respond with ONLY a JSON array, no markdown, no extra text:
            [{"front": "string", "back": "string"}, ...]
        """.trimIndent()

        val WATER_SYSTEM_PROMPT = """
            You are a strict hydration verifier for a water bottle that has a printed
            millilitre scale down its side. You receive two photos of the SAME bottle:
            image 1 is BEFORE drinking, image 2 is AFTER drinking. In each photo find the
            water line (the top surface of the water) and read it against the nearest
            printed ml mark. Decide:
            - same_container: are both photos the SAME physical bottle (shape, colour, cap,
              printed scale, surroundings)? A different bottle = false.
            - scale_visible: is a printed ml scale legible in BOTH photos AND can you read
              the water line against it? If the scale or the water line is unreadable in
              either photo = false.
            - before_level_ml: the water level in image 1, in ml, read from the scale.
            - after_level_ml: the water level in image 2, in ml, read from the scale.
            - consumed_ml: before_level_ml minus after_level_ml (0 if the level did not drop).
            - verified: true ONLY if same_container AND scale_visible AND the level clearly
              dropped from before to after.
            - reason: one short sentence explaining your verdict.
            Bottle size does not matter — read whatever scale is printed, up to 2000 ml.
            Be skeptical: the same level in both photos, an after level HIGHER than before
            (a refill), a different bottle, or a photo of a screen/picture must be rejected.
            Respond with ONLY this JSON object, no markdown, no extra text:
            {"same_container": bool, "scale_visible": bool, "before_level_ml": int,
             "after_level_ml": int, "consumed_ml": int, "verified": bool, "reason": "string"}
        """.trimIndent()
    }
}
