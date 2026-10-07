package app.syncwake.domain.alarm

import java.time.Duration

/**
 * Wake-Up Proof ("Still awake? 👀"). Until Smart Wake Verification ships, every completed alarm
 * falls back to a Wake-Up Proof check after [checkAfter]; this is also the documented fallback
 * whenever Smart Wake Verification cannot run.
 */
data class WakeProofPolicy(
    val enabled: Boolean = true,
    /** Delay between completing the challenge and asking "Still awake?". */
    val checkAfter: Duration = Duration.ofMinutes(5),
    /** How long the user has to answer. */
    val answerWindow: Duration = Duration.ofMinutes(2),
    /** Ring the alarm again if the proof is ignored. */
    val reAlertOnIgnore: Boolean = true,
    /** Upper bound on re-alerts per occurrence, so the loop always terminates. */
    val maxReAlerts: Int = 2,
) {
    init {
        require(!checkAfter.isNegative && checkAfter <= Duration.ofMinutes(30))
        require(answerWindow >= Duration.ofSeconds(30) && answerWindow <= Duration.ofMinutes(10))
        require(maxReAlerts in 0..5)
    }

    fun shouldReAlert(reAlertsSoFar: Int): Boolean = reAlertOnIgnore && reAlertsSoFar < maxReAlerts
}
