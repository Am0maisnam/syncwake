package app.syncwake.domain.alarm

import java.time.Duration
import java.time.Instant

data class SnoozePolicy(
    val snoozeMinutes: Int = 9,
    val maxSnoozes: Int = 3,
) {
    init {
        require(snoozeMinutes in 1..30) { "snoozeMinutes must be 1..30" }
        require(maxSnoozes in 0..10) { "maxSnoozes must be 0..10" }
    }

    fun canSnooze(snoozesSoFar: Int): Boolean = snoozesSoFar < maxSnoozes

    fun snoozeUntil(now: Instant): Instant = now.plus(Duration.ofMinutes(snoozeMinutes.toLong()))
}

/**
 * What to do with an occurrence whose trigger time has passed without it ringing on this device
 * (device was off, app was force-stopped, alarms were cleared by an update...). Evaluated during
 * reconciliation after boot, app update, time change and permission changes.
 */
enum class OverdueDecision {
    /** Still in the future: just schedule it. */
    SCHEDULE,
    /** Slightly late: ring right away rather than silently skipping the morning. */
    RING_NOW,
    /** Too late to be useful: record as missed. */
    MISSED,
}

object OverduePolicy {
    /** How late an alarm may still ring. Matches the default ring duration. */
    val DEFAULT_LATE_RING_GRACE: Duration = Duration.ofMinutes(15)

    fun decide(triggerAt: Instant, now: Instant, grace: Duration = DEFAULT_LATE_RING_GRACE): OverdueDecision = when {
        triggerAt.isAfter(now) -> OverdueDecision.SCHEDULE
        !triggerAt.plus(grace).isBefore(now) -> OverdueDecision.RING_NOW
        else -> OverdueDecision.MISSED
    }
}

/**
 * How long an alarm rings before it is marked missed. The timeout is paused while a challenge is
 * in progress, so it can never cut off a user who is mid-way through dismissing.
 */
data class RingPolicy(val ringDurationMinutes: Int = 15) {
    init {
        require(ringDurationMinutes in 1..60)
    }
}
