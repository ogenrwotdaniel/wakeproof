package com.ogenrwotdaniel.wakeproof.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gemini OpenAI-compatible Chat Completions DTOs (`v1beta/openai/chat/completions`).
 *
 * `content` is a [JsonElement] because the API accepts BOTH a plain string and
 * an array of `{type:"text"}` / `{type:"image_url"}` parts. `temperature` is
 * omitted on Gemini 3.x models per Google's migration guidance; thinking latency
 * is capped via [reasoningEffort] (`"low"`, the minimum supported on
 * `gemini-3.8-flash`), and JSON-returning calls set [responseFormat]
 * (`{"type":"json_object"}`).
 */
@Serializable
data class KimiChatRequest(
    val model: String,
    val messages: List<KimiMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("response_format") val responseFormat: KimiResponseFormat? = null
)

@Serializable
data class KimiResponseFormat(val type: String)

@Serializable
data class KimiMessage(val role: String, val content: JsonElement)

@Serializable
data class KimiChatResponse(
    val id: String? = null,
    val choices: List<KimiChoice> = emptyList()
)

@Serializable
data class KimiChoice(
    val index: Int = 0,
    val message: KimiResponseMessage? = null
)

@Serializable
data class KimiResponseMessage(
    val role: String? = null,
    val content: String? = null
)

/** Plain-text message content. */
fun textContent(text: String): JsonElement = JsonPrimitive(text)

/**
 * Multimodal content: base64 JPEG data URIs followed by the instruction text.
 */
fun imagesWithTextContent(text: String, base64Jpegs: List<String>): JsonElement = buildJsonArray {
    base64Jpegs.forEach { b64 ->
        add(
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$b64") })
            }
        )
    }
    add(buildJsonObject { put("type", "text"); put("text", text) })
}
