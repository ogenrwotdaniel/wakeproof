package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.KIMI_MODEL
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

/** The strict-JSON verdict for the two-photo water check. */
@Serializable
data class WaterVerdict(
    @SerialName("same_container") val sameContainer: Boolean = false,
    @SerialName("capacity_estimate_ml") val capacityEstimateMl: Int = 0,
    @SerialName("photo1_full") val photo1Full: Boolean = false,
    @SerialName("photo2_empty") val photo2Empty: Boolean = false,
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
     * Sends both photos in one request. The returned verdict's [WaterVerdict.verified]
     * is re-enforced locally: same container AND 400–800 ml AND full-then-empty.
     */
    suspend fun verifyWater(fullPhoto: ByteArray, emptyPhoto: ByteArray): KimiResult<WaterVerdict> {
        val encoder = Base64.getEncoder()
        val images = listOf(fullPhoto, emptyPhoto).map {
            encoder.encodeToString(imageCompressor.compressToJpeg(it, MAX_IMAGE_DIMENSION, JPEG_QUALITY))
        }
        val request = KimiChatRequest(
            model = KIMI_MODEL,
            temperature = 0.2,
            messages = listOf(
                KimiMessage("system", textContent(WATER_SYSTEM_PROMPT)),
                KimiMessage(
                    "user",
                    imagesWithTextContent(
                        "Image 1 is the BEFORE photo, image 2 is the AFTER photo. Respond with the JSON only.",
                        images
                    )
                )
            )
        )
        return call(request) { content ->
            val verdict = json.decodeFromString<WaterVerdict>(stripMarkdownFences(content))
            verdict.copy(
                verified = verdict.verified &&
                    verdict.sameContainer &&
                    verdict.capacityEstimateMl in 400..800 &&
                    verdict.photo1Full &&
                    verdict.photo2Empty
            )
        }
    }

    /** 1–2 encouraging sentences; null on any failure (callers show nothing). */
    override suspend fun motivationalMessage(context: String): String? {
        val request = KimiChatRequest(
            model = KIMI_MODEL,
            temperature = 0.7,
            maxTokens = 100,
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
            model = KIMI_MODEL,
            temperature = 0.6,
            maxTokens = 400,
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

    /** Cheapest possible round trip to validate the stored key. */
    suspend fun testConnection(): KimiResult<Unit> {
        val request = KimiChatRequest(
            model = KIMI_MODEL,
            maxTokens = 1,
            messages = listOf(KimiMessage("user", textContent("ping")))
        )
        return when (val result = call(request) { }) {
            is KimiResult.Success -> KimiResult.Success(Unit)
            is KimiResult.ApiError -> result
            KimiResult.NetworkError -> KimiResult.NetworkError
            KimiResult.NoKey -> KimiResult.NoKey
        }
    }

    private suspend fun <T> call(request: KimiChatRequest, transform: (String) -> T): KimiResult<T> {
        if (!keyStore.hasKey()) return KimiResult.NoKey
        var lastFailure: KimiResult<Nothing> = KimiResult.NetworkError
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = api.chatCompletions(request)
                if (response.isSuccessful) {
                    val content = response.body()?.choices?.firstOrNull()?.message?.content
                        ?: return KimiResult.ApiError(null, "Empty response from model")
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

        val WATER_SYSTEM_PROMPT = """
            You are a strict hydration verifier. You receive two photos: photo 1 (before)
            and photo 2 (after). Decide:
            - same_container: are both photos of the SAME physical container (same shape,
              material, labels, surroundings)? Different containers = false.
            - capacity_estimate_ml: your best integer estimate of the container's capacity.
            - photo1_full: is the container in photo 1 essentially full of water?
            - photo2_empty: is the container in photo 2 essentially empty (a few drops ok;
              half-full = false)?
            - verified: true ONLY if same_container AND capacity between 400 and 800 ml
              AND photo1_full AND photo2_empty.
            - reason: one short sentence explaining your verdict.
            Be skeptical: mismatched containers, tiny cups, partially-drunk bottles, or
            photos of screens/pictures must be rejected.
            Respond with ONLY this JSON object, no markdown, no extra text:
            {"same_container": bool, "capacity_estimate_ml": int, "photo1_full": bool,
             "photo2_empty": bool, "verified": bool, "reason": "string"}
        """.trimIndent()
    }
}
