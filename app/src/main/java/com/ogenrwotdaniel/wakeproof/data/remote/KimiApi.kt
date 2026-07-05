package com.ogenrwotdaniel.wakeproof.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Retrofit service for the Kimi OpenAI-compatible endpoint. */
interface KimiApi {
    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: KimiChatRequest): Response<KimiChatResponse>
}
