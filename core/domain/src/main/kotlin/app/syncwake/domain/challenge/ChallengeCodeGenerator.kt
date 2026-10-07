package app.syncwake.domain.challenge

import java.security.SecureRandom

/** Source of randomness, injectable so tests are deterministic. */
fun interface RandomSource {
    /** Returns a uniformly distributed int in [0, bound). */
    fun nextInt(bound: Int): Int
}

class SecureRandomSource(private val random: SecureRandom = SecureRandom()) : RandomSource {
    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}

object ChallengeCodeGenerator {
    const val CODE_LENGTH = 7

    /**
     * Characters that are hard to confuse with each other when half-asleep, in common fonts.
     * Excluded: 0/O/D/Q, 1/I/L/J, 2/Z, 5/S, 8/B, 6/G, U/V, and lowercase entirely
     * (input is case-insensitive).
     */
    const val ALPHABET = "ACEFHKMNPRTWXY34679"

    fun generate(random: RandomSource, length: Int = CODE_LENGTH): String =
        buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
