package com.ogenrwotdaniel.wakeproof.di

import com.ogenrwotdaniel.wakeproof.data.remote.AndroidImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.data.remote.GEMINI_BASE_URL
import com.ogenrwotdaniel.wakeproof.data.remote.ImageCompressor
import com.ogenrwotdaniel.wakeproof.data.remote.KimiApi
import com.ogenrwotdaniel.wakeproof.data.remote.KimiKeyStore
import com.ogenrwotdaniel.wakeproof.verify.MotivationSource
import com.ogenrwotdaniel.wakeproof.verify.OfflineMotivation
import com.ogenrwotdaniel.wakeproof.verify.water.AndroidWaterImageSampler
import com.ogenrwotdaniel.wakeproof.verify.water.LocalFirstWaterObserver
import com.ogenrwotdaniel.wakeproof.verify.water.WaterImageSampler
import com.ogenrwotdaniel.wakeproof.verify.water.WaterObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Gemini API networking and verification singletons. */
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
                val builder = chain.request().newBuilder()
                keyStore.getKey()?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            .build()

    @Provides
    @Singleton
    fun provideKimiApi(client: OkHttpClient): KimiApi {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return Retrofit.Builder()
            .baseUrl(GEMINI_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KimiApi::class.java)
    }

    /**
     * On-device, always. The line is one sentence about a number the app already
     * knows, so there is nothing for a remote model to add — and plenty for a
     * 5 AM network round trip to cost.
     */
    @Provides
    @Singleton
    fun provideMotivationSource(offline: OfflineMotivation): MotivationSource = offline

    @Provides
    @Singleton
    fun provideWaterImageSampler(sampler: AndroidWaterImageSampler): WaterImageSampler = sampler

    /**
     * On-device first, cloud as a second opinion — see [LocalFirstWaterObserver].
     * Unlike motivation the cloud path is deliberately kept: the detector has
     * never been run against a real photograph, and this is what turns a
     * rejection into a question rather than a verdict.
     */
    @Provides
    @Singleton
    fun provideWaterObserver(observer: LocalFirstWaterObserver): WaterObserver = observer
}
