package com.ogenrwotdaniel.wakeproof.data.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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

            val response = try {
                CredentialManager.create(context).getCredential(activity, request)
            } catch (e: GetCredentialCancellationException) {
                throw SignInCancelled("Sign-in cancelled.")
            } catch (e: NoCredentialException) {
                throw SignInCancelled("No Google account is available on this device.")
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
}
