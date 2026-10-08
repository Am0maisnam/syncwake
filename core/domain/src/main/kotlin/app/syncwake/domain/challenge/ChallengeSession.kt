package app.syncwake.domain.challenge

/**
 * One attempt at the dismissal challenge (20 seconds by default). The timer starts at construction, which happens when
 * the user presses Dismiss; the time spent ringing beforehand does not count.
 *
 * Input is validated per character and case-insensitively. A wrong character is not appended; it
 * counts toward [ChallengeConfig.maxWrongCharacters].
 *
 * Not thread-safe; drive it from a single thread (the UI thread).
 */
class ChallengeSession(
    val code: String,
    val config: ChallengeConfig,
    private val startedAtElapsedMillis: Long,
    val arithmetic: ArithmeticQuestion? = null,
) {
    init {
        require(code.length == config.codeLength) { "Code length ${code.length} != ${config.codeLength}" }
        require(config.arithmeticStep == (arithmetic != null)) { "Arithmetic step mismatch" }
    }

    sealed interface Outcome {
        /** The character was correct; [correctCount] characters entered so far. */
        data class Correct(val correctCount: Int) : Outcome
        data class Wrong(val wrongCount: Int) : Outcome
        /** All characters entered; the arithmetic step is next. */
        data object CodeComplete : Outcome
        data object Succeeded : Outcome
        /** The attempt is over and the alarm must keep ringing. */
        data class Failed(val reason: Reason) : Outcome
        /** Input after the session already ended; ignored. */
        data object Ignored : Outcome
    }

    enum class Reason { WRONG_CODE, TIMED_OUT, ABANDONED }

    var correctCount: Int = 0
        private set
    var wrongCount: Int = 0
        private set
    var result: Outcome? = null
        private set

    val isFinished: Boolean get() = result != null
    val codeEntered: Boolean get() = correctCount == code.length

    fun remainingMillis(nowElapsedMillis: Long): Long =
        (startedAtElapsedMillis + config.timeLimitMillis - nowElapsedMillis).coerceAtLeast(0)

    /** Call on every timer tick; ends the attempt once the time limit has passed. */
    fun checkTimeout(nowElapsedMillis: Long): Outcome? {
        if (isFinished) return null
        return if (remainingMillis(nowElapsedMillis) == 0L) finish(Outcome.Failed(Reason.TIMED_OUT)) else null
    }

    fun enter(char: Char, nowElapsedMillis: Long): Outcome {
        if (isFinished) return Outcome.Ignored
        checkTimeout(nowElapsedMillis)?.let { return it }
        if (codeEntered) return Outcome.Ignored
        return if (char.uppercaseChar() == code[correctCount].uppercaseChar()) {
            correctCount++
            when {
                !codeEntered -> Outcome.Correct(correctCount)
                arithmetic != null -> Outcome.CodeComplete
                else -> finish(Outcome.Succeeded)
            }
        } else {
            wrongCount++
            if (wrongCount >= config.maxWrongCharacters) finish(Outcome.Failed(Reason.WRONG_CODE))
            else Outcome.Wrong(wrongCount)
        }
    }

    fun answerArithmetic(answer: Int, nowElapsedMillis: Long): Outcome {
        val question = arithmetic
        if (isFinished || question == null || !codeEntered) return Outcome.Ignored
        checkTimeout(nowElapsedMillis)?.let { return it }
        return if (answer == question.answer) finish(Outcome.Succeeded)
        else finish(Outcome.Failed(Reason.WRONG_CODE))
    }

    /** The user backed out, the screen was closed, or the activity was destroyed. */
    fun abandon(): Outcome = if (isFinished) Outcome.Ignored else finish(Outcome.Failed(Reason.ABANDONED))

    private fun finish(outcome: Outcome): Outcome {
        result = outcome
        return outcome
    }
}
