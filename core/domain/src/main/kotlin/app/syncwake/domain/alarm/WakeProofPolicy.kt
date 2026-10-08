package app.syncwake.domain.alarm

import java.time.Duration

/**
 * Wake-Up Proof ("Still awake? 👀"). Until Smart Wake Verification ships, every completed alarm
 * falls back to a Wake-Up Proof check after [checkAfter]; this is also the documented fallback
 * whenever Smart Wake Verification cannot run.
 */
data class WakeProofPolicy(
    val enabled: Boolean = true,
    /** Delay between completing the challenge and asking "Still awake?". At most [MAX_CHECK_AFTER]. */
    val checkAfter: Duration = MAX_CHECK_AFTER,
    /** How long the user has to answer. */
    val answerWindow: Duration = Duration.ofMinutes(2),
    /** Ring the alarm again if the proof is ignored. */
    val reAlertOnIgnore: Boolean = true,
    /** Upper bound on re-alerts per occurrence, so the loop always terminates. */
    val maxReAlerts: Int = 2,
) {
    init {
        require(!checkAfter.isNegative && checkAfter <= MAX_CHECK_AFTER) { "checkAfter must be 0..$MAX_CHECK_AFTER" }
        require(answerWindow >= Duration.ofSeconds(30) && answerWindow <= Duration.ofMinutes(10))
        require(maxReAlerts in 0..5)
    }

    fun shouldReAlert(reAlertsSoFar: Int): Boolean = reAlertOnIgnore && reAlertsSoFar < maxReAlerts

    companion object {
        /** Wake tracking after the alarm is kept short: the check happens within 1 minute. */
        val MAX_CHECK_AFTER: Duration = Duration.ofMinutes(1)
    }
}
