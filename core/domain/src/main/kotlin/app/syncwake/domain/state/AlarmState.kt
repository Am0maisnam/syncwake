package app.syncwake.domain.state

/**
 * Lifecycle of a single alarm occurrence on one device, for one user.
 *
 * Pressing "Dismiss" never completes an alarm by itself: it only starts a [DISMISS_CHALLENGE].
 * An occurrence counts as [COMPLETED] only after the challenge succeeds.
 */
enum class AlarmState {
    SCHEDULED,
    RINGING,
    SNOOZED,
    DISMISS_CHALLENGE,
    CHALLENGE_FAILED,
    COMPLETED,
    PASSIVE_WAKE_MONITORING,
    PROBABLY_AWAKE,
    WAKE_STATUS_UNKNOWN,
    WAKE_PROOF_REQUIRED,
    CONFIRMED_AWAKE,
    MISSED,
    CANCELLED;

    /** No further transitions are possible. */
    val isTerminal: Boolean get() = this == CONFIRMED_AWAKE || this == MISSED || this == CANCELLED

    /** The alarm sound should be playing in this state (it is lowered, not stopped, during a challenge). */
    val isAudible: Boolean get() = this == RINGING || this == DISMISS_CHALLENGE || this == CHALLENGE_FAILED

    /** The user has proven they were awake at alarm time (the challenge was solved). */
    val challengeCompleted: Boolean
        get() = this == COMPLETED || this == PASSIVE_WAKE_MONITORING || this == PROBABLY_AWAKE ||
            this == WAKE_STATUS_UNKNOWN || this == WAKE_PROOF_REQUIRED || this == CONFIRMED_AWAKE
}

enum class ChallengeFailureReason { WRONG_CODE, TIMED_OUT, ABANDONED }

sealed interface AlarmEvent {
    /** The scheduled (or snoozed) time arrived and the alarm started ringing. */
    data object Fire : AlarmEvent
    data object Snooze : AlarmEvent
    /** The user pressed Dismiss; the 15-second challenge timer starts now. */
    data object BeginChallenge : AlarmEvent
    data object ChallengeSucceeded : AlarmEvent
    data class ChallengeFailed(val reason: ChallengeFailureReason) : AlarmEvent
    /** After a failure, the alarm goes back to ringing at full volume. */
    data object ResumeRinging : AlarmEvent
    /** Rang for the full ring duration without being completed, or never rang (device off). */
    data object MarkMissed : AlarmEvent
    data object Cancel : AlarmEvent

    // Wake verification (post-completion).
    data object StartWakeMonitoring : AlarmEvent
    data object WakeActivityPartial : AlarmEvent
    data object WakeActivityConfirmed : AlarmEvent
    data object WakeActivityInsufficient : AlarmEvent
    data object RequireWakeProof : AlarmEvent
    data object WakeProofConfirmed : AlarmEvent
    /** Wake-Up Proof went unanswered and the policy says to ring again. */
    data object WakeProofIgnoredReAlert : AlarmEvent
    /** Wake-Up Proof went unanswered and the policy says to stop asking. */
    data object WakeProofIgnoredGiveUp : AlarmEvent
}

class IllegalAlarmTransition(val from: AlarmState, val event: AlarmEvent) :
    IllegalStateException("Illegal alarm transition: $from --$event-->")
