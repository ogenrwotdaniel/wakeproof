package com.ogenrwotdaniel.wakeproof.data.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/** The Google account the local data mirrors to, if any. */
sealed interface SyncAccount {
    data object SignedOut : SyncAccount

    data class SignedIn(
        val uid: String,
        val email: String?,
        val displayName: String?
    ) : SyncAccount
}

/** Sign-in was cancelled by the user, or no usable account was offered. */
class SignInCancelled(message: String) : Exception(message)

/**
 * Google sign-in for cloud sync, over Credential Manager.
 *
 * Signing in is the *only* thing that enables cross-device sync, and it is
 * strictly opt-in: everything the app does works signed out, on-device.
 */
@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val availability: CloudSyncAvailability,
    private val auth: FirebaseAuth?,
    private val syncPreferences: SyncPreferences
) {

    /**
     * The current account, re-emitted whenever Firebase's auth state changes —
     * including a token expiring or the account being revoked from another device,
     * neither of which goes through [signIn].
     */
    val account: Flow<SyncAccount> = auth?.let { firebaseAuth ->
        callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser.toAccount()) }
            firebaseAuth.addAuthStateListener(listener)
            awaitClose { firebaseAuth.removeAuthStateListener(listener) }
        }
    } ?: flowOf(SyncAccount.SignedOut)

    fun currentUid(): String? = auth?.currentUser?.uid

    /**
     * Shows the system account picker and exchanges the chosen Google identity for
     * a Firebase session. Needs an [Activity] — Credential Manager renders into the
     * activity's window, so an application context will not do.
     */
    suspend fun signIn(activity: Activity): Result<SyncAccount> {
        val firebaseAuth = auth
        val clientId = availability.webClientId
        if (firebaseAuth == null || clientId == null) {
            return Result.failure(IllegalStateException("This build has no Firebase configuration."))
        }
        return runCatching {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(clientId)
                // false: also offer accounts that have never used this app, so the
                // very first sign-in on a device has something to show.
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(context)
            val response = try {
                credentialManager.getCredential(activity, request)
            } catch (e: GetCredentialCancellationException) {
                // Not necessarily the user: after a sign-out, Google's one-tap sheet
                // enters a cool-down and answers instantly with a cancellation
                // instead of showing anything. That is why sign-in works on a fresh
                // device, then refuses on the one that signed out and tried again.
                // The explicit "Sign in with Google" flow is not rate-limited, so
                // fall back to it before believing the cancellation.
                explicitSignIn(credentialManager, activity, clientId)
                    ?: throw SignInCancelled("Sign-in cancelled.")
            } catch (e: NoCredentialException) {
                explicitSignIn(credentialManager, activity, clientId)
                    ?: throw SignInCancelled("No Google account is available on this device.")
            } catch (e: GetCredentialException) {
                // Everything else from Credential Manager, and the reason this is not
                // folded into the NoCredentialException branch: an unregistered signing
                // certificate arrives here as "[28444] Developer console is not set up
                // correctly", and reporting that as a missing account sends whoever hit
                // it to their device settings instead of the Firebase console. Play App
                // Signing makes it the DEFAULT state of a fresh Play track until that
                // key's SHA-1 is added — see docs/CLOUD_SYNC.md step 3.
                throw SignInCancelled(
                    "Google could not offer an account for this build " +
                        "(${e.type}). Sync is unavailable; everything else works."
                )
            }

            val googleCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
            val firebaseCredential =
                GoogleAuthProvider.getCredential(googleCredential.idToken, null)
            val previousUid = firebaseAuth.currentUser?.uid
            val user = firebaseAuth.signInWithCredential(firebaseCredential).await().user
                ?: error("Firebase returned no user.")

            // Switching accounts must not inherit the old account's read cursors,
            // or the new account's existing data would be treated as already seen.
            if (previousUid != null && previousUid != user.uid) syncPreferences.clearCursors()
            syncPreferences.setEnabled(true)
            user.toAccount()
        }
    }

    /**
     * A fresh Google ID token for the account this device already syncs as, to
     * hand to the watch (Settings -> Sync -> "Sign in the watch").
     *
     * Asks only among accounts that have used this app and auto-selects when
     * there is one, so in practice it is a brief sheet rather than a picker.
     * The chosen account must be the one signed in here: a watch on a different
     * account would upload into a tree no other device reads.
     */
    suspend fun idTokenForWatch(activity: Activity): Result<String> {
        val clientId = availability.webClientId
        val email = auth?.currentUser?.email
        if (clientId == null || auth?.currentUser == null) {
            return Result.failure(IllegalStateException("Sign in on this device first."))
        }
        return runCatching {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(
                    GetGoogleIdOption.Builder()
                        .setServerClientId(clientId)
                        .setFilterByAuthorizedAccounts(true)
                        .setAutoSelectEnabled(true)
                        .build()
                )
                .build()
            val credentialManager = CredentialManager.create(context)
            val response = try {
                credentialManager.getCredential(activity, request)
            } catch (e: GetCredentialCancellationException) {
                // Same post-sign-out cool-down as in signIn().
                explicitSignIn(credentialManager, activity, clientId)
                    ?: throw SignInCancelled("Cancelled.")
            } catch (e: NoCredentialException) {
                explicitSignIn(credentialManager, activity, clientId)
                    ?: throw SignInCancelled("No Google account is available on this device.")
            }
            val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
            if (email != null && !credential.id.equals(email, ignoreCase = true)) {
                throw SignInCancelled("Pick $email — the account this device syncs with.")
            }
            credential.idToken
        }
    }

    /**
     * The account-picker flow behind the "Sign in with Google" button, used when
     * the one-tap sheet declines to appear.
     *
     * Returns null if this one is cancelled too — that cancellation is the user's,
     * since this flow always draws something to cancel.
     */
    private suspend fun explicitSignIn(
        credentialManager: CredentialManager,
        activity: Activity,
        clientId: String
    ): GetCredentialResponse? {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        return try {
            credentialManager.getCredential(activity, request)
        } catch (e: GetCredentialException) {
            // Log the exception so device-specific failures (Samsung one-tap / Play
            // Services issues) are visible in adb logcat for debugging. The flow
            // treats a failure here as a user cancellation, but we still want to
            // record the reason when debugging why sign-in refused to show.
            Log.w(TAG, "explicitSignIn GetCredentialException: ${e.type} - ${e.message}", e)
            null
        }
    }

    /**
     * Signs out and forgets how far this device had read. Local rows are left
     * alone — the user keeps their data; it simply stops mirroring.
     */
    suspend fun signOut() {
        auth?.signOut()
        syncPreferences.setEnabled(false)
        syncPreferences.clearCursors()
    }

    private fun com.google.firebase.auth.FirebaseUser?.toAccount(): SyncAccount =
        if (this == null) {
            SyncAccount.SignedOut
        } else {
            SyncAccount.SignedIn(uid = uid, email = email, displayName = displayName)
        }

    private companion object {
        const val TAG = "AccountRepository"
    }
}
