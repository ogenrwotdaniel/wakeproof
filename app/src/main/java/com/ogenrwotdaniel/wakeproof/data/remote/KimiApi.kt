package com.ogenrwotdaniel.wakeproof.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Retrofit service for the Google Gemini OpenAI-compatible endpoint. */
interface GeminiApi {
    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: GeminiChatRequest): Response<GeminiChatResponse>
}

typealias KimiApi = GeminiApi
