package com.ogenrwotdaniel.wakeproof.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI-compatible Chat Completions DTOs. `content` is a [JsonElement]
 * because the API accepts BOTH a plain string and an array of
 * {type:"text"} / {type:"image_url"} parts.
 */
@Serializable
data class KimiChatRequest(
    val model: String,
    val messages: List<KimiMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

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
 * Multimodal content: the images (as base64 data URIs — Kimi does not fetch
 * URLs) followed by the instruction text.
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
