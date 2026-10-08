package app.syncwake.domain.challenge

/**
 * Parameters for one dismissal attempt. Produced by [EscalationPolicy]; never hard-coded in UI.
 *
 * @property timeLimitMillis measured from when the user pressed Dismiss, not from when the alarm
 *   started ringing.
 * @property maxWrongCharacters wrong key presses tolerated in one attempt before it counts as a
 *   wrong code.
 * @property arithmeticStep when true, a simple addition must be answered after the code.
 */
data class ChallengeConfig(
    val codeLength: Int = ChallengeCodeGenerator.CODE_LENGTH,
    val timeLimitMillis: Long = DEFAULT_TIME_LIMIT_MILLIS,
    val maxWrongCharacters: Int = 3,
    val arithmeticStep: Boolean = false,
    val escalationLevel: Int = 0,
) {
    companion object {
        const val DEFAULT_TIME_LIMIT_MILLIS = 20_000L
    }
}

/** User-controlled accommodations. */
data class ChallengeAccessibility(
    /** Multiplies the time limit (e.g. 2.0 doubles 20s to 40s). Clamped to 1.0..4.0. */
    val timeMultiplier: Double = 1.0,
    /** Never add the arithmetic step, however many attempts fail. */
    val disableCognitiveEscalation: Boolean = false,
)

/**
 * Escalation after repeated failures. The core challenge always stays 7 characters; escalation
 * only adds a small, always-solvable step. Escalation is capped so the alarm can never become
 * impossible to dismiss.
 */
object EscalationPolicy {
    /** Failures (within one occurrence) after which the arithmetic step is added. */
    const val ARITHMETIC_AFTER_FAILURES = 3

    fun configFor(failuresSoFar: Int, accessibility: ChallengeAccessibility = ChallengeAccessibility()): ChallengeConfig {
        require(failuresSoFar >= 0)
        val multiplier = accessibility.timeMultiplier.coerceIn(1.0, 4.0)
        val timeLimit = (ChallengeConfig.DEFAULT_TIME_LIMIT_MILLIS * multiplier).toLong()
        val escalate = failuresSoFar >= ARITHMETIC_AFTER_FAILURES && !accessibility.disableCognitiveEscalation
        return ChallengeConfig(
            timeLimitMillis = timeLimit,
            arithmeticStep = escalate,
            escalationLevel = if (escalate) 1 else 0,
        )
    }
}

/** A simple addition, e.g. "23 + 7". Always two small positive numbers. */
data class ArithmeticQuestion(val a: Int, val b: Int) {
    val answer: Int get() = a + b
    val prompt: String get() = "$a + $b"

    companion object {
        fun generate(random: RandomSource): ArithmeticQuestion =
            ArithmeticQuestion(a = 10 + random.nextInt(40), b = 2 + random.nextInt(8))
    }
}
