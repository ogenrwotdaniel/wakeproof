package com.ogenrwotdaniel.wakeproof.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write access to the AI provider choice and its API keys. Keys are kept
 * per provider, so switching Kimi ↔ Gemini doesn't lose the other's key.
 */
interface ApiKeyStore {
    fun getKey(): String?
    fun setKey(value: String)
    fun clear()
    fun hasKey(): Boolean = getKey() != null
    fun getProvider(): AiProvider
    fun setProvider(provider: AiProvider)
}

/**
 * Stores the key in EncryptedSharedPreferences — never in code, never in the
 * regular (backup-able) prefs. security-crypto is deprecated upstream but is
 * this project's mandated storage; revisit if the library is removed.
 */
@Singleton
class KimiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) : ApiKeyStore {

    @Suppress("DEPRECATION")
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kimi_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getKey(): String? =
        prefs.getString(keyName(getProvider()), null)?.takeIf { it.isNotBlank() }

    override fun setKey(value: String) {
        prefs.edit().putString(keyName(getProvider()), value.trim()).apply()
    }

    override fun clear() {
        prefs.edit().remove(keyName(getProvider())).apply()
    }

    override fun getProvider(): AiProvider =
        AiProvider.fromId(prefs.getString(KEY_PROVIDER, null))

    override fun setProvider(provider: AiProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.id).apply()
    }

    /** Kimi keeps the pre-provider-era pref name so existing keys survive. */
    private fun keyName(provider: AiProvider) =
        if (provider == AiProvider.KIMI) KEY_API_LEGACY else "api_key_${provider.id}"

    private companion object {
        const val KEY_API_LEGACY = "kimi_api_key"
        const val KEY_PROVIDER = "ai_provider"
    }
}
