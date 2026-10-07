package app.syncwake.domain.alarm

import java.time.Duration

/**
 * Custom and voice alarm sounds are gentle by nature, so they get a limited head start. If the
 * alarm is still ringing after [customSoundLimit] — the person hasn't started dismissing it — the
 * built-in alarm tone takes over for the rest of that ring.
 *
 * - The clock starts when the alarm starts ringing (each ring, including after a snooze).
 * - Starting the dismissal challenge is the sign of being awake; the switch is not made while a
 *   challenge is in progress. If the challenge fails after the limit, ringing resumes on the
 *   built-in tone.
 * - Once switched, it never goes back to the custom sound during that ring.
 */
data class AlarmSoundEscalation(val customSoundLimit: Duration = Duration.ofMinutes(1)) {
    init {
        require(customSoundLimit >= Duration.ofSeconds(10) && customSoundLimit <= Duration.ofMinutes(10)) {
            "customSoundLimit must be between 10 seconds and 10 minutes"
        }
    }

    enum class Sound { CUSTOM, DEFAULT }

    fun soundFor(hasCustomSound: Boolean, ringingFor: Duration, alreadyEscalated: Boolean): Sound =
        if (!hasCustomSound || alreadyEscalated || ringingFor >= customSoundLimit) Sound.DEFAULT else Sound.CUSTOM

    /** Time left before switching, or null if there is nothing to switch from. */
    fun timeUntilSwitch(hasCustomSound: Boolean, ringingFor: Duration, alreadyEscalated: Boolean): Duration? =
        if (soundFor(hasCustomSound, ringingFor, alreadyEscalated) == Sound.CUSTOM) customSoundLimit.minus(ringingFor) else null
}
