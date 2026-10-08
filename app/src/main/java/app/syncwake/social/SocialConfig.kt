package app.syncwake.social

import android.content.Context
import android.util.Log
import app.syncwake.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** Build-time social settings (see docs/BACKEND_SETUP.md). Empty values disable the feature. */
data class SocialConfig(
    val apiBaseUrl: String,
    val googleServerClientId: String,
    val firebaseAppId: String,
    val firebaseApiKey: String,
    val firebaseProjectId: String,
    val firebaseSenderId: String,
) {
    /** Accounts and shared alarms need the API and Google sign-in. */
    val isConfigured: Boolean get() = apiBaseUrl.isNotBlank() && googleServerClientId.isNotBlank()

    /** Push is optional: without it, friends' status still syncs every ~15 minutes and on open. */
    val pushConfigured: Boolean
        get() = firebaseAppId.isNotBlank() && firebaseApiKey.isNotBlank() && firebaseProjectId.isNotBlank() && firebaseSenderId.isNotBlank()

    /**
     * Initialises Firebase from build config instead of google-services.json, so the project builds
     * without that file. Idempotent. Must only be called after the user has unlocked the device
     * (Firebase uses credential-protected storage).
     */
    fun ensureFirebase(context: Context): Boolean {
        if (!pushConfigured) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return try {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(firebaseAppId)
                    .setApiKey(firebaseApiKey)
                    .setProjectId(firebaseProjectId)
                    .setGcmSenderId(firebaseSenderId)
                    .build(),
            )
            true
        } catch (e: Exception) {
            Log.e("SocialConfig", "Firebase init failed", e)
            false
        }
    }

    companion object {
        val fromBuild = SocialConfig(
            apiBaseUrl = BuildConfig.API_BASE_URL.trimEnd('/'),
            googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
            firebaseAppId = BuildConfig.FIREBASE_APP_ID,
            firebaseApiKey = BuildConfig.FIREBASE_API_KEY,
            firebaseProjectId = BuildConfig.FIREBASE_PROJECT_ID,
            firebaseSenderId = BuildConfig.FIREBASE_SENDER_ID,
        )
    }
}
