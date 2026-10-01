package app.syncwake.domain.state

import app.syncwake.domain.state.AlarmEvent.BeginChallenge
import app.syncwake.domain.state.AlarmEvent.ChallengeFailed
import app.syncwake.domain.state.AlarmEvent.ChallengeSucceeded
import app.syncwake.domain.state.AlarmEvent.Fire
import app.syncwake.domain.state.AlarmState.CHALLENGE_FAILED
import app.syncwake.domain.state.AlarmState.COMPLETED
import app.syncwake.domain.state.AlarmState.CONFIRMED_AWAKE
import app.syncwake.domain.state.AlarmState.DISMISS_CHALLENGE
import app.syncwake.domain.state.AlarmState.RINGING
import app.syncwake.domain.state.AlarmState.SCHEDULED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmStateMachineTest {
    private fun run(vararg events: AlarmEvent, from: AlarmState = SCHEDULED): AlarmState =
        events.fold(from) { s, e -> AlarmStateMachine.transition(s, e) }

    @Test
    fun happyPathThroughWakeProof() {
        val end = run(
            Fire, BeginChallenge, ChallengeSucceeded,
            AlarmEvent.RequireWakeProof, AlarmEvent.WakeProofConfirmed,
        )
        assertEquals(CONFIRMED_AWAKE, end)
    }

    @Test
    fun smartWakePath() {
        assertEquals(
            CONFIRMED_AWAKE,
            run(
                Fire, BeginChallenge, ChallengeSucceeded, AlarmEvent.StartWakeMonitoring,
                AlarmEvent.WakeActivityPartial, AlarmEvent.WakeActivityPartial, AlarmEvent.WakeActivityConfirmed,
            ),
        )
        assertEquals(
            AlarmState.WAKE_PROOF_REQUIRED,
            run(
                Fire, BeginChallenge, ChallengeSucceeded, AlarmEvent.StartWakeMonitoring,
                AlarmEvent.WakeActivityInsufficient, AlarmEvent.RequireWakeProof,
            ),
        )
    }

    @Test
    fun dismissButtonAloneNeverCompletes() {
        assertFalse(AlarmStateMachine.canTransition(RINGING, ChallengeSucceeded))
        assertEquals(DISMISS_CHALLENGE, run(Fire, BeginChallenge))
    }

    @Test
    fun everyFailureReasonReturnsToRinging() {
        for (reason in ChallengeFailureReason.entries) {
            val failed = run(Fire, BeginChallenge, ChallengeFailed(reason))
            assertEquals(CHALLENGE_FAILED, failed)
            assertEquals(RINGING, AlarmStateMachine.transition(failed, AlarmEvent.ResumeRinging))
            assertFalse(failed.challengeCompleted)
        }
    }

    @Test
    fun repeatedFailuresThenSuccess() {
        var s = run(Fire)
        repeat(5) {
            s = run(BeginChallenge, ChallengeFailed(ChallengeFailureReason.TIMED_OUT), AlarmEvent.ResumeRinging, from = s)
        }
        assertEquals(RINGING, s)
        assertEquals(COMPLETED, run(BeginChallenge, ChallengeSucceeded, from = s))
    }

    @Test
    fun snoozeThenRingAgain() {
        assertEquals(RINGING, run(Fire, AlarmEvent.Snooze, Fire))
    }

    @Test
    fun ignoredWakeProofCanReAlert() {
        val s = run(Fire, BeginChallenge, ChallengeSucceeded, AlarmEvent.RequireWakeProof, AlarmEvent.WakeProofIgnoredReAlert)
        assertEquals(RINGING, s)
        assertEquals(DISMISS_CHALLENGE, run(BeginChallenge, from = s))
    }

    @Test
    fun terminalStatesRejectEverything() {
        val events = listOf(
            Fire, BeginChallenge, ChallengeSucceeded, AlarmEvent.Snooze, AlarmEvent.Cancel,
            AlarmEvent.MarkMissed, AlarmEvent.ResumeRinging, AlarmEvent.WakeProofConfirmed,
        )
        for (state in AlarmState.entries.filter { it.isTerminal }) {
            for (e in events) assertFalse("$state/$e", AlarmStateMachine.canTransition(state, e))
        }
    }

    @Test
    fun completedAlarmCannotBeCancelledOrMissed() {
        assertFalse(AlarmStateMachine.canTransition(COMPLETED, AlarmEvent.Cancel))
        assertFalse(AlarmStateMachine.canTransition(COMPLETED, AlarmEvent.MarkMissed))
    }

    @Test(expected = IllegalAlarmTransition::class)
    fun illegalTransitionThrows() {
        AlarmStateMachine.transition(SCHEDULED, ChallengeSucceeded)
    }

    @Test
    fun audibleStates() {
        assertTrue(RINGING.isAudible)
        assertTrue(DISMISS_CHALLENGE.isAudible)
        assertFalse(AlarmState.SNOOZED.isAudible)
        assertFalse(COMPLETED.isAudible)
    }
}
