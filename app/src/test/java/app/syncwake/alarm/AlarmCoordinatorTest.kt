package app.syncwake.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.syncwake.data.AlarmEntity
import app.syncwake.data.AlarmRepository
import app.syncwake.data.SyncWakeDatabase
import app.syncwake.data.alarmState
import app.syncwake.data.daysToMask
import app.syncwake.domain.state.AlarmEvent
import app.syncwake.domain.state.AlarmState
import app.syncwake.domain.time.WallClock
import app.syncwake.settings.AppSettings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmCoordinatorTest {
    private val zone = ZoneId.of("America/New_York")
    private val friday = LocalDate.of(2026, 10, 2)

    private class FakeClock(var instant: Instant, private val zone: ZoneId) : WallClock {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = zone
    }

    private lateinit var context: Context
    private lateinit var db: SyncWakeDatabase
    private lateinit var repo: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var clock: FakeClock
    private lateinit var coordinator: AlarmCoordinator
    private lateinit var alarmManager: AlarmManager

    private fun at(date: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(date.atTime(h, m), zone).toInstant()

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SyncWakeDatabase::class.java).allowMainThreadQueries().build()
        repo = AlarmRepository(db)
        scheduler = AlarmScheduler(context)
        clock = FakeClock(at(friday, 6), zone)
        val wakeProof = WakeProofManager(context, repo, scheduler, AppSettings(context), clock)
        coordinator = AlarmCoordinator(context, repo, scheduler, wakeProof, clock)
        alarmManager = context.getSystemService(AlarmManager::class.java)
    }

    @After
    fun tearDown() = db.close()

    private fun alarm(days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), oneTimeDate: LocalDate? = null) = AlarmEntity(
        alarmId = "a1", label = "Run", hour = 7, minute = 0,
        repeatDaysMask = daysToMask(days), oneTimeDate = oneTimeDate?.toString(), anchorZone = null,
        enabled = true, vibrate = true, soundUri = null, snoozeMinutes = 9, maxSnoozes = 3,
        ringDurationMinutes = 15, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun savingAlarmSchedulesExactAlarmClock() = runBlocking {
        coordinator.saveAlarm(alarm())
        val occ = repo.occurrence("a1@$friday")
        assertNotNull(occ)
        assertEquals(AlarmState.SCHEDULED, occ!!.alarmState)
        assertTrue(scheduler.isRingRegistered("a1@$friday"))
        assertEquals(at(friday, 7).toEpochMilli(), alarmManager.nextAlarmClock?.triggerTime)
    }

    @Test
    fun rebootLosesRegistrationAndReconcileRestoresIt() = runBlocking {
        coordinator.saveAlarm(alarm())
        scheduler.cancelRing("a1@$friday") // what a reboot does to every AlarmManager registration
        assertFalse(scheduler.isRingRegistered("a1@$friday"))
        coordinator.reconcile()
        assertTrue(scheduler.isRingRegistered("a1@$friday"))
    }

    @Test
    fun deviceOffThroughAlarmMarksMissedAndSchedulesTomorrow() = runBlocking {
        coordinator.saveAlarm(alarm())
        clock.instant = at(friday, 10)
        coordinator.reconcile()
        assertEquals(AlarmState.MISSED, repo.occurrence("a1@$friday")!!.alarmState)
        val saturday = friday.plusDays(1)
        assertTrue(scheduler.isRingRegistered("a1@$saturday"))
    }

    @Test
    fun slightlyLateRebootRingsImmediately() = runBlocking {
        coordinator.saveAlarm(alarm())
        clock.instant = at(friday, 7, 4)
        coordinator.reconcile()
        // Still SCHEDULED in the database, re-registered to fire now.
        assertEquals(AlarmState.SCHEDULED, repo.occurrence("a1@$friday")!!.alarmState)
        assertTrue(scheduler.isRingRegistered("a1@$friday"))
    }

    @Test
    fun disablingCancelsRegistration() = runBlocking {
        coordinator.saveAlarm(alarm())
        coordinator.setEnabled("a1", false)
        assertFalse(scheduler.isRingRegistered("a1@$friday"))
        assertEquals(AlarmState.CANCELLED, repo.occurrence("a1@$friday")!!.alarmState)
        assertNull(alarmManager.nextAlarmClock)
    }

    @Test
    fun completedOccurrenceIsNeverRescheduled() = runBlocking {
        coordinator.saveAlarm(alarm(days = emptySet(), oneTimeDate = friday))
        val id = "a1@$friday"
        repo.transition(id, AlarmEvent.Fire, 0)
        repo.transition(id, AlarmEvent.BeginChallenge, 0)
        repo.transition(id, AlarmEvent.ChallengeSucceeded, 0)
        coordinator.reconcile()
        assertEquals(AlarmState.COMPLETED, repo.occurrence(id)!!.alarmState)
    }

    @Test
    fun illegalTransitionIsRejectedAndNotLogged() = runBlocking {
        coordinator.saveAlarm(alarm())
        val id = "a1@$friday"
        assertNull(repo.transition(id, AlarmEvent.ChallengeSucceeded, 0))
        assertEquals(AlarmState.SCHEDULED, repo.occurrence(id)!!.alarmState)
        assertTrue(repo.eventsFor(id).isEmpty())
        assertNotNull(repo.transition(id, AlarmEvent.Fire, 0))
        assertEquals(1, repo.eventsFor(id).size)
    }

    @Test
    fun ignoredWakeProofReAlertsThroughAlarmManager() = runBlocking {
        coordinator.saveAlarm(alarm())
        val id = "a1@$friday"
        clock.instant = at(friday, 7)
        repo.transition(id, AlarmEvent.Fire, 0)
        repo.transition(id, AlarmEvent.BeginChallenge, 0)
        repo.transition(id, AlarmEvent.ChallengeSucceeded, 0) { it.copy(wakeProofCheckAt = at(friday, 7, 5).toEpochMilli()) }
        val wakeProof = WakeProofManager(context, repo, scheduler, AppSettings(context), clock)
        clock.instant = at(friday, 7, 5)
        wakeProof.onCheckDue(id)
        assertEquals(AlarmState.WAKE_PROOF_REQUIRED, repo.occurrence(id)!!.alarmState)
        clock.instant = at(friday, 7, 8)
        wakeProof.onDeadline(id)
        assertEquals(AlarmState.RINGING, repo.occurrence(id)!!.alarmState)
        assertTrue(scheduler.isRingRegistered(id))
    }
}
