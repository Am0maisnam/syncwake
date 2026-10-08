package app.syncwake.social

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class SignInCancelled : Exception("Sign-in cancelled")

class SignInFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One-tap Google sign-in through Android's Credential Manager; returns a Google ID token. */
object GoogleSignIn {
    suspend fun idToken(activity: Activity, serverClientId: String): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw SignInCancelled()
        } catch (e: NoCredentialException) {
            throw SignInFailed("No Google account found on this phone. Add one in Settings → Accounts.", e)
        } catch (e: GetCredentialException) {
            throw SignInFailed("Google sign-in failed: ${e.message}", e)
        }
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw SignInFailed("Unexpected credential type")
    }
}
