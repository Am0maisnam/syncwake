package app.syncwake.domain.state

import app.syncwake.domain.state.AlarmEvent.BeginChallenge
import app.syncwake.domain.state.AlarmEvent.Cancel
import app.syncwake.domain.state.AlarmEvent.ChallengeFailed
import app.syncwake.domain.state.AlarmEvent.ChallengeSucceeded
import app.syncwake.domain.state.AlarmEvent.Fire
import app.syncwake.domain.state.AlarmEvent.MarkMissed
import app.syncwake.domain.state.AlarmEvent.RequireWakeProof
import app.syncwake.domain.state.AlarmEvent.ResumeRinging
import app.syncwake.domain.state.AlarmEvent.Snooze
import app.syncwake.domain.state.AlarmEvent.StartWakeMonitoring
import app.syncwake.domain.state.AlarmEvent.WakeActivityConfirmed
import app.syncwake.domain.state.AlarmEvent.WakeActivityInsufficient
import app.syncwake.domain.state.AlarmEvent.WakeActivityPartial
import app.syncwake.domain.state.AlarmEvent.WakeProofConfirmed
import app.syncwake.domain.state.AlarmEvent.WakeProofIgnoredGiveUp
import app.syncwake.domain.state.AlarmEvent.WakeProofIgnoredReAlert
import app.syncwake.domain.state.AlarmState.CANCELLED
import app.syncwake.domain.state.AlarmState.CHALLENGE_FAILED
import app.syncwake.domain.state.AlarmState.COMPLETED
import app.syncwake.domain.state.AlarmState.CONFIRMED_AWAKE
import app.syncwake.domain.state.AlarmState.DISMISS_CHALLENGE
import app.syncwake.domain.state.AlarmState.MISSED
import app.syncwake.domain.state.AlarmState.PASSIVE_WAKE_MONITORING
import app.syncwake.domain.state.AlarmState.PROBABLY_AWAKE
import app.syncwake.domain.state.AlarmState.RINGING
import app.syncwake.domain.state.AlarmState.SCHEDULED
import app.syncwake.domain.state.AlarmState.SNOOZED
import app.syncwake.domain.state.AlarmState.WAKE_PROOF_REQUIRED
import app.syncwake.domain.state.AlarmState.WAKE_STATUS_UNKNOWN

/**
 * Explicit transition table for an alarm occurrence. Every state change in the app goes through
 * [transition]; anything not listed here is rejected, which keeps illegal shortcuts (for example
 * RINGING -> COMPLETED without a challenge) impossible by construction.
 */
object AlarmStateMachine {

    fun transition(from: AlarmState, event: AlarmEvent): AlarmState =
        next(from, event) ?: throw IllegalAlarmTransition(from, event)

    fun canTransition(from: AlarmState, event: AlarmEvent): Boolean = next(from, event) != null

    private fun next(from: AlarmState, event: AlarmEvent): AlarmState? = when (from) {
        SCHEDULED -> when (event) {
            Fire -> RINGING
            MarkMissed -> MISSED
            Cancel -> CANCELLED
            else -> null
        }
        RINGING -> when (event) {
            BeginChallenge -> DISMISS_CHALLENGE
            Snooze -> SNOOZED
            MarkMissed -> MISSED
            Cancel -> CANCELLED
            Fire -> RINGING // duplicate delivery (e.g. service restarted) is harmless
            else -> null
        }
        SNOOZED -> when (event) {
            Fire -> RINGING
            MarkMissed -> MISSED
            Cancel -> CANCELLED
            else -> null
        }
        DISMISS_CHALLENGE -> when (event) {
            ChallengeSucceeded -> COMPLETED
            is ChallengeFailed -> CHALLENGE_FAILED
            Cancel -> CANCELLED
            else -> null
        }
        CHALLENGE_FAILED -> when (event) {
            ResumeRinging -> RINGING
            Cancel -> CANCELLED
            else -> null
        }
        COMPLETED -> when (event) {
            StartWakeMonitoring -> PASSIVE_WAKE_MONITORING
            RequireWakeProof -> WAKE_PROOF_REQUIRED
            WakeActivityConfirmed -> CONFIRMED_AWAKE
            else -> null
        }
        PASSIVE_WAKE_MONITORING -> when (event) {
            WakeActivityPartial -> PROBABLY_AWAKE
            WakeActivityConfirmed -> CONFIRMED_AWAKE
            WakeActivityInsufficient -> WAKE_STATUS_UNKNOWN
            else -> null
        }
        PROBABLY_AWAKE -> when (event) {
            WakeActivityPartial -> PROBABLY_AWAKE
            WakeActivityConfirmed -> CONFIRMED_AWAKE
            WakeActivityInsufficient -> WAKE_STATUS_UNKNOWN
            else -> null
        }
        WAKE_STATUS_UNKNOWN -> when (event) {
            RequireWakeProof -> WAKE_PROOF_REQUIRED
            else -> null
        }
        WAKE_PROOF_REQUIRED -> when (event) {
            WakeProofConfirmed -> CONFIRMED_AWAKE
            WakeProofIgnoredReAlert -> RINGING
            WakeProofIgnoredGiveUp -> WAKE_STATUS_UNKNOWN
            else -> null
        }
        CONFIRMED_AWAKE, MISSED, CANCELLED -> null
    }
}
