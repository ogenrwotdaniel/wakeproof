package com.ogenrwotdaniel.wakeproof.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Read/write access to the user's Kimi API key. */
interface ApiKeyStore {
    fun getKey(): String?
    fun setKey(value: String)
    fun clear()
    fun hasKey(): Boolean = getKey() != null
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

    override fun getKey(): String? = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    override fun setKey(value: String) {
        prefs.edit().putString(KEY_API, value.trim()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_API).apply()
    }

    private companion object {
        const val KEY_API = "kimi_api_key"
    }
}
