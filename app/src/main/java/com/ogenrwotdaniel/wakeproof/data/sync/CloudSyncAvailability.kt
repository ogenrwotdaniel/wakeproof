package com.ogenrwotdaniel.wakeproof.data.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.ogenrwotdaniel.wakeproof.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this build can talk to Firebase at all.
 *
 * `google-services.json` is untracked, so a fresh clone builds an app with no
 * Firebase project behind it. Rather than let that surface as a crash the first
 * time someone taps "Sign in", the whole sync feature checks here and presents
 * itself as unavailable.
 *
 * Both halves are needed: [BuildConfig.FIREBASE_CONFIGURED] says the config file
 * was present at build time, and [FirebaseApp.getApps] confirms the resulting
 * values actually initialised an app at runtime.
 */
@Singleton
class CloudSyncAvailability @Inject constructor(@ApplicationContext private val context: Context) {

    val isAvailable: Boolean by lazy {
        BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()
    }

    /**
     * The OAuth *web* client id Google sign-in must be asked for — not the Android
     * client id. The google-services plugin emits it as a string resource, which
     * only exists when the plugin ran, so it is looked up by name instead of
     * through `R` to keep a Firebase-less build compiling.
     */
    val webClientId: String? by lazy {
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    /** Sign-in additionally needs the web client id; storage alone is not enough. */
    val canSignIn: Boolean get() = isAvailable && webClientId != null
}
