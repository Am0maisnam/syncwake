package app.syncwake.domain.social

import app.syncwake.domain.state.AlarmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicStatusMapperTest {
    @Test
    fun mapsTheMorningToHighLevelEvents() {
        assertEquals(SocialEvent.StatusChanged(PublicStatus.RINGING), PublicStatusMapper.eventFor(AlarmState.RINGING))
        assertEquals(SocialEvent.StatusChanged(PublicStatus.COMPLETING_CHALLENGE), PublicStatusMapper.eventFor(AlarmState.DISMISS_CHALLENGE))
        assertEquals(SocialEvent.AlarmCompleted, PublicStatusMapper.eventFor(AlarmState.COMPLETED))
        assertEquals(SocialEvent.StatusChanged(PublicStatus.WAKE_PROOF_REQUIRED), PublicStatusMapper.eventFor(AlarmState.WAKE_PROOF_REQUIRED))
        assertEquals(SocialEvent.WakeProofCompleted, PublicStatusMapper.eventFor(AlarmState.CONFIRMED_AWAKE))
        assertEquals(SocialEvent.StatusChanged(PublicStatus.MISSED), PublicStatusMapper.eventFor(AlarmState.MISSED))
    }

    @Test
    fun transientAndPrivateStatesAreNotShared() {
        assertNull(PublicStatusMapper.eventFor(AlarmState.CHALLENGE_FAILED))
        assertNull(PublicStatusMapper.eventFor(AlarmState.SCHEDULED))
        assertNull(PublicStatusMapper.eventFor(AlarmState.CANCELLED))
    }

    @Test
    fun everyStateIsHandled() {
        // Compile-time exhaustive `when`; this just exercises every branch.
        AlarmState.entries.forEach { PublicStatusMapper.eventFor(it) }
    }

    @Test
    fun unknownServerStatusFallsBackSafely() {
        assertEquals(PublicStatus.AWAKE_CONFIRMED, PublicStatusMapper.parse("AWAKE_CONFIRMED"))
        assertEquals(PublicStatus.SCHEDULED, PublicStatusMapper.parse("SOMETHING_NEW"))
        assertEquals(PublicStatus.SCHEDULED, PublicStatusMapper.parse(null))
    }
}
