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
     * client id. Extracted from `google-services.json` at build time (see
     * app/build.gradle.kts) rather than read from the generated string resource:
     * that resource can only be reached by name, and the resource shrinker deleted
     * it from the release build because nothing referenced it statically.
     */
    val webClientId: String? by lazy { BuildConfig.WEB_CLIENT_ID.takeIf { it.isNotBlank() } }

    /** Sign-in additionally needs the web client id; storage alone is not enough. */
    val canSignIn: Boolean get() = isAvailable && webClientId != null
}
