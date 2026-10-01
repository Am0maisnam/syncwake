package app.syncwake.domain.challenge

import app.syncwake.domain.challenge.ChallengeSession.Outcome
import app.syncwake.domain.challenge.ChallengeSession.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTest {
    private fun session(start: Long = 1_000, config: ChallengeConfig = ChallengeConfig()) =
        ChallengeSession("K7MHR9X", config, start)

    @Test
    fun generatedCodeHasSevenUnambiguousCharacters() {
        val generated = (1..500).map { ChallengeCodeGenerator.generate(SecureRandomSource()) }
        for (c in generated) {
            assertEquals(7, c.length)
            assertTrue(c, c.all { it in ChallengeCodeGenerator.ALPHABET })
        }
        for (ambiguous in "0O1IL5S8B2Z") assertFalse(ambiguous in ChallengeCodeGenerator.ALPHABET)
        assertTrue("codes should vary", generated.toSet().size > 450)
    }

    @Test
    fun generatorUsesRandomSource() {
        assertEquals("AAAAAAA", ChallengeCodeGenerator.generate(RandomSource { 0 }))
        assertNotEquals("AAAAAAA", ChallengeCodeGenerator.generate(RandomSource { it - 1 }))
    }

    @Test
    fun correctCodeCaseInsensitiveSucceeds() {
        val s = session()
        val outcomes = "k7mhr9x".mapIndexed { i, ch -> s.enter(ch, 1_000L + i * 500) }
        assertEquals((1..6).map { Outcome.Correct(it) }, outcomes.dropLast(1))
        assertEquals(Outcome.Succeeded, outcomes.last())
        assertTrue(s.isFinished)
    }

    @Test
    fun timerStartsAtDismissNotAtRing() {
        // The alarm may have rung for minutes; the session started at t=600_000 still has 15s.
        val s = session(start = 600_000)
        assertEquals(15_000, s.remainingMillis(600_000))
        assertEquals(1_000, s.remainingMillis(614_000))
    }

    @Test
    fun timeoutAtFifteenSecondsFails() {
        val s = session(start = 0)
        assertNull(s.checkTimeout(14_999))
        assertEquals(Outcome.Failed(Reason.TIMED_OUT), s.checkTimeout(15_000))
        assertEquals(Outcome.Ignored, s.enter('K', 15_001))
    }

    @Test
    fun lateCharacterAfterExpiryFails() {
        val s = session(start = 0)
        "K7MHR9".forEachIndexed { i, ch -> s.enter(ch, i * 100L) }
        assertEquals(Outcome.Failed(Reason.TIMED_OUT), s.enter('X', 15_000))
    }

    @Test
    fun wrongCharactersAreNotAppendedAndFailAfterLimit() {
        val s = session()
        assertEquals(Outcome.Correct(1), s.enter('K', 1_000))
        assertEquals(Outcome.Wrong(1), s.enter('A', 1_100))
        assertEquals(1, s.correctCount)
        assertEquals(Outcome.Wrong(2), s.enter('A', 1_200))
        assertEquals(Outcome.Failed(Reason.WRONG_CODE), s.enter('A', 1_300))
        assertEquals(Outcome.Ignored, s.enter('7', 1_400))
    }

    @Test
    fun abandonFails() {
        val s = session()
        s.enter('K', 1_000)
        assertEquals(Outcome.Failed(Reason.ABANDONED), s.abandon())
        assertEquals(Outcome.Ignored, s.abandon())
    }

    @Test
    fun arithmeticStepRequiredWhenEscalated() {
        val config = EscalationPolicy.configFor(failuresSoFar = 3)
        assertTrue(config.arithmeticStep)
        val s = ChallengeSession("K7MHR9X", config, 0, ArithmeticQuestion(20, 5))
        "K7MHR9".forEach { s.enter(it, 100) }
        assertEquals(Outcome.CodeComplete, s.enter('X', 200))
        assertFalse(s.isFinished)
        assertEquals(Outcome.Succeeded, s.answerArithmetic(25, 300))
    }

    @Test
    fun wrongArithmeticFails() {
        val s = ChallengeSession("K7MHR9X", EscalationPolicy.configFor(5), 0, ArithmeticQuestion(20, 5))
        "K7MHR9X".forEach { s.enter(it, 100) }
        assertEquals(Outcome.Failed(Reason.WRONG_CODE), s.answerArithmetic(24, 300))
    }

    @Test
    fun escalationIsCappedAndCodeStaysSeven() {
        for (failures in 0..50) {
            val config = EscalationPolicy.configFor(failures)
            assertEquals(7, config.codeLength)
            assertEquals(15_000, config.timeLimitMillis)
            assertTrue(config.escalationLevel <= 1)
        }
        assertFalse(EscalationPolicy.configFor(2).arithmeticStep)
    }

    @Test
    fun accessibilityExtendsTimeAndDisablesCognitiveStep() {
        val a11y = ChallengeAccessibility(timeMultiplier = 2.0, disableCognitiveEscalation = true)
        val config = EscalationPolicy.configFor(10, a11y)
        assertEquals(30_000, config.timeLimitMillis)
        assertFalse(config.arithmeticStep)
        // Multiplier is clamped: it can never shorten the limit.
        assertEquals(15_000, EscalationPolicy.configFor(0, ChallengeAccessibility(timeMultiplier = 0.1)).timeLimitMillis)
    }

    @Test
    fun arithmeticQuestionsAreSmall() {
        var i = 0
        repeat(100) {
            val q = ArithmeticQuestion.generate { bound -> (i++) % bound }
            assertTrue(q.a in 10..49 && q.b in 2..9)
        }
    }

    @Test
    fun brightnessIncreasesMonotonicallyToMax() {
        val curve = BrightnessCurve(startLevel = 0.2f, maxLevel = 0.9f)
        val levels = (0..7).map { curve.levelFor(it, 7) }
        assertEquals(0.2f, levels.first(), 1e-6f)
        assertEquals(0.9f, levels.last(), 1e-6f)
        assertTrue(levels.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun brightnessRespectsReducedChangesAndBounds() {
        val curve = BrightnessCurve(startLevel = 0.3f, maxLevel = 1f, reduceBrightnessChanges = true)
        assertTrue((0..7).all { curve.levelFor(it, 7) == 0.3f })
        val capped = BrightnessCurve(startLevel = 0.8f, maxLevel = 0.5f)
        assertTrue((0..7).all { capped.levelFor(it, 7) <= 0.5f })
        assertEquals(1f, BrightnessCurve().levelFor(99, 7), 1e-6f)
    }
}
