package app.syncwake.domain.alarm

import app.syncwake.domain.alarm.AlarmSoundEscalation.Sound
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmSoundEscalationTest {
    private val policy = AlarmSoundEscalation()

    @Test
    fun customSoundPlaysForTheFirstMinute() {
        assertEquals(Sound.CUSTOM, policy.soundFor(true, Duration.ZERO, false))
        assertEquals(Sound.CUSTOM, policy.soundFor(true, Duration.ofSeconds(59), false))
    }

    @Test
    fun defaultToneTakesOverAfterOneMinute() {
        assertEquals(Sound.DEFAULT, policy.soundFor(true, Duration.ofSeconds(60), false))
        assertEquals(Sound.DEFAULT, policy.soundFor(true, Duration.ofMinutes(5), false))
    }

    @Test
    fun noCustomSoundMeansDefaultFromTheStart() {
        assertEquals(Sound.DEFAULT, policy.soundFor(false, Duration.ZERO, false))
        assertNull(policy.timeUntilSwitch(false, Duration.ZERO, false))
    }

    @Test
    fun switchIsSticky() {
        assertEquals(Sound.DEFAULT, policy.soundFor(true, Duration.ZERO, alreadyEscalated = true))
        assertNull(policy.timeUntilSwitch(true, Duration.ZERO, alreadyEscalated = true))
    }

    @Test
    fun timeUntilSwitchCountsDown() {
        assertEquals(Duration.ofSeconds(60), policy.timeUntilSwitch(true, Duration.ZERO, false))
        assertEquals(Duration.ofSeconds(15), policy.timeUntilSwitch(true, Duration.ofSeconds(45), false))
        assertNull(policy.timeUntilSwitch(true, Duration.ofSeconds(60), false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun limitIsBounded() {
        AlarmSoundEscalation(Duration.ofSeconds(1))
    }
}
