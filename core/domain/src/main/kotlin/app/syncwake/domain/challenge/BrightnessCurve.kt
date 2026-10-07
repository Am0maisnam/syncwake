package app.syncwake.domain.challenge

/**
 * Screen brightness during the challenge. Each correct character raises brightness linearly from
 * the starting level to [maxLevel]. Values are window-brightness fractions in 0.0..1.0, applied to
 * the alarm screen only (never to the global system setting) and reset when the screen closes.
 */
data class BrightnessCurve(
    val startLevel: Float = 0.15f,
    val maxLevel: Float = 1.0f,
    /** Accessibility: keep brightness constant (at [startLevel]) during the challenge. */
    val reduceBrightnessChanges: Boolean = false,
) {
    init {
        require(startLevel in 0f..1f && maxLevel in 0f..1f)
    }

    fun levelFor(correctCount: Int, codeLength: Int): Float {
        require(codeLength > 0)
        val start = startLevel.coerceAtMost(maxLevel)
        if (reduceBrightnessChanges) return start
        val progress = correctCount.coerceIn(0, codeLength).toFloat() / codeLength
        return start + (maxLevel - start) * progress
    }
}
