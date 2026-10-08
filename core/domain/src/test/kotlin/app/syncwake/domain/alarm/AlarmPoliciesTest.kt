package app.syncwake.domain.alarm

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPoliciesTest {
    private val t = Instant.parse("2026-10-02T11:00:00Z")

    @Test
    fun overdueDecisions() {
        assertEquals(OverdueDecision.SCHEDULE, OverduePolicy.decide(t.plusSeconds(1), t))
        assertEquals(OverdueDecision.RING_NOW, OverduePolicy.decide(t, t))
        assertEquals(OverdueDecision.RING_NOW, OverduePolicy.decide(t.minus(Duration.ofMinutes(15)), t))
        assertEquals(OverdueDecision.MISSED, OverduePolicy.decide(t.minus(Duration.ofMinutes(16)), t))
    }

    @Test
    fun snoozeLimit() {
        val policy = SnoozePolicy(snoozeMinutes = 5, maxSnoozes = 2)
        assertTrue(policy.canSnooze(0))
        assertTrue(policy.canSnooze(1))
        assertFalse(policy.canSnooze(2))
        assertEquals(t.plusSeconds(300), policy.snoozeUntil(t))
    }

    @Test
    fun alarmsRingForTwoMinutesByDefault() {
        assertEquals(2, RingPolicy().ringDurationMinutes)
        assertEquals(2, RingPolicy.DEFAULT_RING_MINUTES)
    }

    @Test
    fun wakeProofCheckHappensWithinOneMinute() {
        assertEquals(Duration.ofMinutes(1), WakeProofPolicy().checkAfter)
        WakeProofPolicy(checkAfter = Duration.ofSeconds(30)) // allowed
    }

    @Test(expected = IllegalArgumentException::class)
    fun wakeProofCheckLaterThanOneMinuteIsRejected() {
        WakeProofPolicy(checkAfter = Duration.ofSeconds(61))
    }

    @Test
    fun wakeProofReAlertIsBounded() {
        val policy = WakeProofPolicy(maxReAlerts = 2)
        assertTrue(policy.shouldReAlert(0))
        assertTrue(policy.shouldReAlert(1))
        assertFalse(policy.shouldReAlert(2))
        assertFalse(WakeProofPolicy(reAlertOnIgnore = false).shouldReAlert(0))
    }
}
