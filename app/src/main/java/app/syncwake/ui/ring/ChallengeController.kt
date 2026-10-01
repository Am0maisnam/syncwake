package app.syncwake.ui.ring

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.syncwake.domain.challenge.ArithmeticQuestion
import app.syncwake.domain.challenge.ChallengeAccessibility
import app.syncwake.domain.challenge.ChallengeCodeGenerator
import app.syncwake.domain.challenge.ChallengeSession
import app.syncwake.domain.challenge.ChallengeSession.Outcome
import app.syncwake.domain.challenge.EscalationPolicy
import app.syncwake.domain.challenge.SecureRandomSource

/**
 * Compose-observable wrapper around one [ChallengeSession]. Created when the user presses Dismiss,
 * which is when the 15-second timer starts.
 */
class ChallengeController(failuresSoFar: Int, accessibility: ChallengeAccessibility) {
    private val random = SecureRandomSource()
    val startedAtWallMillis: Long = System.currentTimeMillis()
    private val config = EscalationPolicy.configFor(failuresSoFar, accessibility)
    val session = ChallengeSession(
        code = ChallengeCodeGenerator.generate(random, config.codeLength),
        config = config,
        startedAtElapsedMillis = SystemClock.elapsedRealtime(),
        arithmetic = if (config.arithmeticStep) ArithmeticQuestion.generate(random) else null,
    )

    val code: String get() = session.code
    val timeLimitMillis: Long get() = config.timeLimitMillis
    val escalationLevel: Int get() = config.escalationLevel

    var correctCount by mutableIntStateOf(0)
        private set
    var wrongCount by mutableIntStateOf(0)
        private set
    var remainingMillis by mutableLongStateOf(config.timeLimitMillis)
        private set
    var arithmeticInput by mutableStateOf("")
        private set
    /** Incremented on every wrong key, so the UI can flash. */
    var wrongPulse by mutableIntStateOf(0)
        private set

    val inArithmeticStep: Boolean get() = session.codeEntered && session.arithmetic != null && !session.isFinished

    fun tick(): Outcome? {
        val now = SystemClock.elapsedRealtime()
        remainingMillis = session.remainingMillis(now)
        return session.checkTimeout(now)
    }

    fun enter(char: Char): Outcome {
        val outcome = session.enter(char, SystemClock.elapsedRealtime())
        sync(outcome)
        return outcome
    }

    fun typeDigit(digit: Char) {
        if (arithmeticInput.length < 3) arithmeticInput += digit
    }

    fun backspace() {
        arithmeticInput = arithmeticInput.dropLast(1)
    }

    fun submitArithmetic(): Outcome {
        val answer = arithmeticInput.toIntOrNull() ?: return Outcome.Ignored
        return session.answerArithmetic(answer, SystemClock.elapsedRealtime()).also { sync(it) }
    }

    fun abandon(): Outcome = session.abandon()

    private fun sync(outcome: Outcome) {
        correctCount = session.correctCount
        wrongCount = session.wrongCount
        if (outcome is Outcome.Wrong) wrongPulse++
    }
}
