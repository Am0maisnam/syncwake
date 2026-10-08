package app.syncwake.domain.social

import app.syncwake.domain.state.AlarmState

/**
 * The only states friends ever see. Raw activity (movement, screen, unlocks) has no
 * representation here, so it can't be shared by mistake. Mirrors the backend's PUBLIC_STATUSES.
 */
enum class PublicStatus(val emoji: String, val label: String) {
    SCHEDULED("🕒", "Not ringing yet"),
    RINGING("🔔", "Alarm ringing"),
    SNOOZED("💤", "Snoozed"),
    COMPLETING_CHALLENGE("⌨️", "Completing challenge"),
    WAKE_VERIFICATION("⏳", "Wake verification"),
    WAKE_PROOF_REQUIRED("👀", "Wake proof required"),
    AWAKE_CONFIRMED("✅", "Awake confirmed"),
    MISSED("❌", "Missed"),
}

/** What a device uploads for a shared alarm when its local occurrence changes state. */
sealed interface SocialEvent {
    val type: String

    data class StatusChanged(val status: PublicStatus) : SocialEvent {
        override val type = "STATUS_CHANGED"
    }

    /** The challenge was solved; the server records it as this user's completion. */
    data object AlarmCompleted : SocialEvent {
        override val type = "ALARM_COMPLETED"
    }

    data object WakeProofCompleted : SocialEvent {
        override val type = "WAKE_PROOF_COMPLETED"
    }
}

object PublicStatusMapper {
    /** The event to share when an occurrence enters [state], or null when friends needn't know. */
    fun eventFor(state: AlarmState): SocialEvent? = when (state) {
        AlarmState.RINGING -> SocialEvent.StatusChanged(PublicStatus.RINGING)
        AlarmState.SNOOZED -> SocialEvent.StatusChanged(PublicStatus.SNOOZED)
        AlarmState.DISMISS_CHALLENGE -> SocialEvent.StatusChanged(PublicStatus.COMPLETING_CHALLENGE)
        AlarmState.COMPLETED -> SocialEvent.AlarmCompleted
        AlarmState.PASSIVE_WAKE_MONITORING, AlarmState.PROBABLY_AWAKE ->
            SocialEvent.StatusChanged(PublicStatus.WAKE_VERIFICATION)
        AlarmState.WAKE_PROOF_REQUIRED -> SocialEvent.StatusChanged(PublicStatus.WAKE_PROOF_REQUIRED)
        AlarmState.CONFIRMED_AWAKE -> SocialEvent.WakeProofCompleted
        AlarmState.MISSED -> SocialEvent.StatusChanged(PublicStatus.MISSED)
        // Transient (a failed attempt goes straight back to RINGING) or not meaningful to friends.
        AlarmState.CHALLENGE_FAILED, AlarmState.WAKE_STATUS_UNKNOWN, AlarmState.SCHEDULED, AlarmState.CANCELLED -> null
    }

    fun parse(name: String?): PublicStatus = PublicStatus.entries.firstOrNull { it.name == name } ?: PublicStatus.SCHEDULED
}
