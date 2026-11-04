package com.ogenrwotdaniel.wakeproof.clip

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the clip feature needs to know about its own configuration. */
data class ClipConfig(
    val key: ByteArray?,
    val enabled: Boolean,
    val sendScreenshots: Boolean,
    val computerName: String?,
    val computerInstallId: String?,
    /** Where the paired computer was reachable at pairing time — a LAN peer even signed out. */
    val computerAddrs: List<String>,
    val computerUid: String?
) {
    val paired: Boolean get() = key != null
    /** On and paired: the only state in which anything listens or sends. */
    val active: Boolean get() = enabled && paired

    override fun equals(other: Any?): Boolean = other is ClipConfig &&
        (key?.contentEquals(other.key) ?: (other.key == null)) &&
        enabled == other.enabled && sendScreenshots == other.sendScreenshots &&
        computerName == other.computerName && computerInstallId == other.computerInstallId &&
        computerAddrs == other.computerAddrs && computerUid == other.computerUid

    override fun hashCode(): Int = (key?.contentHashCode() ?: 0) * 31 + enabled.hashCode()
}

/**
 * The clip key K and the pairing it came from, in EncryptedSharedPreferences
 * (same pattern as `KimiKeyStore`). K never leaves this store except inside a
 * pairing exchange; it never goes to the cloud.
 */
@Singleton
class ClipKeyStore @Inject constructor(@ApplicationContext private val context: Context) {

    @Suppress("DEPRECATION")
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "clip_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val state by lazy { MutableStateFlow(read()) }

    /** The current configuration; re-emitted on every change made through this store. */
    val config: StateFlow<ClipConfig> get() = state.asStateFlow()

    fun current(): ClipConfig = state.value

    /** Adopts K_final and the computer that issued it. Turns the feature on. */
    fun adoptPairing(key: ByteArray, response: PairResponse, addrs: List<String>) = write {
        putString(KEY_K, ClipCodec.b64url(key))
        putString(KEY_COMPUTER_NAME, response.name)
        putString(KEY_COMPUTER_ID, response.installId)
        putString(KEY_COMPUTER_ADDRS, addrs.joinToString(","))
        if (response.uid != null) putString(KEY_COMPUTER_UID, response.uid) else remove(KEY_COMPUTER_UID)
        putBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(value: Boolean) = write { putBoolean(KEY_ENABLED, value) }

    fun setSendScreenshots(value: Boolean) = write { putBoolean(KEY_SCREENSHOTS, value) }

    /** Deletes K and the pairing; this device only. */
    fun forget() = write {
        remove(KEY_K)
        remove(KEY_COMPUTER_NAME)
        remove(KEY_COMPUTER_ID)
        remove(KEY_COMPUTER_ADDRS)
        remove(KEY_COMPUTER_UID)
    }

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true, action = block)
        state.value = read()
    }

    private fun read(): ClipConfig = ClipConfig(
        key = prefs.getString(KEY_K, null)?.let { runCatching { ClipCodec.fromB64url(it) }.getOrNull() }
            ?.takeIf { it.size == ClipCodec.KEY_BYTES },
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        sendScreenshots = prefs.getBoolean(KEY_SCREENSHOTS, true),
        computerName = prefs.getString(KEY_COMPUTER_NAME, null),
        computerInstallId = prefs.getString(KEY_COMPUTER_ID, null),
        computerAddrs = prefs.getString(KEY_COMPUTER_ADDRS, null).orEmpty()
            .split(',').filter { it.isNotBlank() },
        computerUid = prefs.getString(KEY_COMPUTER_UID, null)
    )

    private companion object {
        const val KEY_K = "clip_key"
        const val KEY_ENABLED = "enabled"
        const val KEY_SCREENSHOTS = "send_screenshots"
        const val KEY_COMPUTER_NAME = "computer_name"
        const val KEY_COMPUTER_ID = "computer_install_id"
        const val KEY_COMPUTER_ADDRS = "computer_addrs"
        const val KEY_COMPUTER_UID = "computer_uid"
    }
}
