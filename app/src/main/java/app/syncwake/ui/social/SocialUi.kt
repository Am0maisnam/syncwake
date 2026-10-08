package app.syncwake.ui.social

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import app.syncwake.social.ApiException
import app.syncwake.social.SignInCancelled
import app.syncwake.social.SignInFailed
import java.io.IOException

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Human wording for social errors. Returns null when nothing should be shown (user cancelled). */
fun socialErrorMessage(e: Throwable): String? = when (e) {
    is SignInCancelled -> null
    is SignInFailed -> e.message
    is ApiException -> when (e.code) {
        "group_full" -> "This alarm is full. Free alarms can have up to 3 people."
        "invite_not_found" -> "That code doesn't exist. Check it and try again."
        "invite_expired" -> "That code has expired. Ask for a new one."
        "removed_from_alarm" -> "You were removed from this alarm."
        "alarm_cancelled" -> "This alarm was cancelled by its owner."
        "version_conflict" -> "Someone changed this alarm at the same time. It's been reloaded; try again."
        "owner_only" -> "Only the alarm's owner can do that."
        "not_signed_in", "invalid_token" -> "Please sign in again."
        "sign_in_not_configured", "invalid_google_token" -> "Google sign-in isn't set up correctly on the server."
        else -> "Something went wrong (${e.code})."
    }
    is IOException -> "Couldn't reach SyncWake. Check your internet connection."
    else -> e.message ?: "Something went wrong."
}
