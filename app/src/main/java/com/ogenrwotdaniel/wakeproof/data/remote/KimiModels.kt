package com.ogenrwotdaniel.wakeproof.data.remote

/**
 * Kimi (Moonshot AI) endpoint constants. Moonshot rotates model ids
 * (K2.5/K2.6 generation as of mid-2026); check platform.moonshot.ai before
 * release.
 */
const val KIMI_BASE_URL = "https://api.moonshot.ai/v1/"
const val KIMI_MODEL = "kimi-k2.5"

/**
 * Selectable OpenAI-compatible chat/vision backends. Both speak the same
 * `chat/completions` wire format (including base64 `image_url` parts), so the
 * whole difference is base URL + model id + which key the interceptor sends.
 * Gemini's AI Studio tier is free (no card) — the escape hatch for users who
 * don't want to pay for Kimi.
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val keyHint: String,
    val keySource: String
) {
    GEMINI(
        id = "gemini",
        label = "Gemini (free)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        model = "gemini-2.5-flash",
        keyHint = "AIza…",
        keySource = "aistudio.google.com/apikey (free, no card needed)"
    ),
    KIMI(
        id = "kimi",
        label = "Kimi (paid)",
        baseUrl = KIMI_BASE_URL,
        model = KIMI_MODEL,
        keyHint = "sk-…",
        keySource = "platform.moonshot.ai"
    );

    companion object {
        /** Unset (fresh install) resolves to the free provider. */
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}
