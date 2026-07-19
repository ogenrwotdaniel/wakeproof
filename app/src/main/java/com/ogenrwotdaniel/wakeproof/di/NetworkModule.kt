package com.ogenrwotdaniel.wakeproof.di

import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.remote.AiProvider
import com.ogenrwotdaniel.wakeproof.data.remote.AndroidImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.KIMI_BASE_URL
import com.ogenrwotdaniel.wakeproof.data.remote.KimiApi
import com.ogenrwotdaniel.wakeproof.data.remote.KimiKeyStore
import com.ogenrwotdaniel.wakeproof.verify.MotivationSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Kimi API networking singletons. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideApiKeyStore(store: KimiKeyStore): ApiKeyStore = store

    @Provides
    @Singleton
    fun provideImageCompressor(): ImageCompressor = AndroidImageCompressor()

    @Provides
    @Singleton
    fun provideOkHttpClient(keyStore: ApiKeyStore): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // Retrofit is built against the Kimi base URL; other providers are
                // OpenAI-compatible too, so we just swap host+path per request.
                // chat/completions is the API's only endpoint.
                val provider = keyStore.getProvider()
                val builder = chain.request().newBuilder()
                if (provider != AiProvider.KIMI) {
                    builder.url((provider.baseUrl + "chat/completions").toHttpUrl())
                }
                keyStore.getKey()?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            .build()

    @Provides
    @Singleton
    fun provideKimiApi(client: OkHttpClient): KimiApi {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return Retrofit.Builder()
            .baseUrl(KIMI_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KimiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMotivationSource(repository: KimiRepository): MotivationSource = repository
}
