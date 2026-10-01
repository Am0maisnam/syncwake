package app.syncwake.domain.alarm

import app.syncwake.domain.alarm.ReconcileAction.Cancel
import app.syncwake.domain.alarm.ReconcileAction.DisableOneTimeAlarm
import app.syncwake.domain.alarm.ReconcileAction.MarkMissed
import app.syncwake.domain.alarm.ReconcileAction.RingNow
import app.syncwake.domain.alarm.ReconcileAction.Schedule
import app.syncwake.domain.alarm.ReconcileAction.ScheduleSnooze
import app.syncwake.domain.state.AlarmState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReconcilerTest {
    private val ny = ZoneId.of("America/New_York")
    private val chicago = ZoneId.of("America/Chicago")
    private val fri = LocalDate.of(2026, 10, 2) // a Friday
    private val sat = fri.plusDays(1)
    private val everyDay = AlarmSchedule(LocalTime.of(7, 0), DayOfWeek.entries.toSet(), null)
    private val alarm = AlarmRecord("a1", everyDay, enabled = true)

    private fun at(date: LocalDate, h: Int, m: Int = 0, zone: ZoneId = ny): Instant =
        ZonedDateTime.of(date, LocalTime.of(h, m), zone).toInstant()

    private fun occ(date: LocalDate, state: AlarmState = AlarmState.SCHEDULED, snoozeUntil: Instant? = null, firedAt: Instant? = null) =
        PendingOccurrence(occurrenceId("a1", date), "a1", date, at(date, 7), state, snoozeUntil, firedAt)

    private fun reconcile(
        pending: List<PendingOccurrence>,
        now: Instant,
        alarms: List<AlarmRecord> = listOf(alarm),
        zone: ZoneId = ny,
        audible: Set<String> = emptySet(),
    ) = AlarmReconciler.reconcile(alarms, pending, now, zone, audible)

    @Test
    fun freshAlarmIsScheduled() {
        val actions = reconcile(emptyList(), at(fri, 6))
        assertEquals(listOf(Schedule("a1", "a1@$fri", fri, at(fri, 7))), actions)
    }

    @Test
    fun existingFutureOccurrenceIsReRegisteredAfterReboot() {
        val actions = reconcile(listOf(occ(fri)), at(fri, 6))
        assertEquals(listOf(Schedule("a1", "a1@$fri", fri, at(fri, 7))), actions)
    }

    @Test
    fun rebootJustAfterAlarmTimeRingsLate() {
        val actions = reconcile(listOf(occ(fri)), at(fri, 7, 5))
        assertEquals(listOf(RingNow("a1@$fri"), Schedule("a1", "a1@$sat", sat, at(sat, 7))), actions)
    }

    @Test
    fun deviceOffAllMorningRecordsMissed() {
        val actions = reconcile(listOf(occ(fri)), at(fri, 9))
        assertEquals(listOf(MarkMissed("a1@$fri"), Schedule("a1", "a1@$sat", sat, at(sat, 7))), actions)
    }

    @Test
    fun interruptedRingingResumesOrIsMissed() {
        val ringing = occ(fri, AlarmState.DISMISS_CHALLENGE, firedAt = at(fri, 7))
        assertEquals(RingNow("a1@$fri"), reconcile(listOf(ringing), at(fri, 7, 3)).first())
        assertEquals(MarkMissed("a1@$fri"), reconcile(listOf(ringing), at(fri, 8)).first())
    }

    @Test
    fun audibleOccurrenceInThisProcessIsLeftAlone() {
        val ringing = occ(fri, AlarmState.RINGING, firedAt = at(fri, 7))
        val actions = reconcile(listOf(ringing), at(fri, 7, 1), audible = setOf("a1@$fri"))
        assertEquals(listOf(Schedule("a1", "a1@$sat", sat, at(sat, 7))), actions)
    }

    @Test
    fun snoozedOccurrenceIsReRegistered() {
        val snoozed = occ(fri, AlarmState.SNOOZED, snoozeUntil = at(fri, 7, 9))
        assertEquals(ScheduleSnooze("a1@$fri", at(fri, 7, 9)), reconcile(listOf(snoozed), at(fri, 7, 2)).first())
        assertEquals(RingNow("a1@$fri"), reconcile(listOf(snoozed), at(fri, 7, 12)).first())
    }

    @Test
    fun disablingCancelsPendingOccurrence() {
        val actions = reconcile(listOf(occ(fri)), at(fri, 6), alarms = listOf(alarm.copy(enabled = false)))
        assertEquals(listOf(Cancel("a1@$fri")), actions)
    }

    @Test
    fun deletedAlarmCancelsOccurrence() {
        assertEquals(listOf(Cancel("a1@$fri")), reconcile(listOf(occ(fri)), at(fri, 6), alarms = emptyList()))
    }

    @Test
    fun editingRepeatDaysReplacesOccurrence() {
        val weekendsOnly = alarm.copy(schedule = everyDay.copy(repeatDays = setOf(DayOfWeek.SATURDAY)))
        val actions = reconcile(listOf(occ(fri)), at(fri, 6), alarms = listOf(weekendsOnly))
        assertEquals(listOf(Cancel("a1@$fri"), Schedule("a1", "a1@$sat", sat, at(sat, 7))), actions)
    }

    @Test
    fun editingTimeUpdatesTriggerOfSameOccurrence() {
        val later = alarm.copy(schedule = everyDay.copy(time = LocalTime.of(7, 30)))
        val actions = reconcile(listOf(occ(fri)), at(fri, 6), alarms = listOf(later))
        assertEquals(listOf(Schedule("a1", "a1@$fri", fri, at(fri, 7, 30))), actions)
    }

    @Test
    fun timeZoneChangeMovesFloatingAlarm() {
        // Flew NY -> Chicago at 06:00 NY time (05:00 Chicago): 07:00 now means 07:00 Chicago.
        val actions = reconcile(listOf(occ(fri)), at(fri, 6), zone = chicago)
        assertEquals(listOf(Schedule("a1", "a1@$fri", fri, at(fri, 7, zone = chicago))), actions)
    }

    @Test
    fun flyingEastCanMakeAnAlarmOverdue() {
        // Pending 07:00 NY occurrence; 06:10 NY is 07:10 in Halifax (one hour ahead).
        val halifax = ZoneId.of("America/Halifax")
        assertEquals(RingNow("a1@$fri"), reconcile(listOf(occ(fri)), at(fri, 6, 10), zone = halifax).first())
        // 06:30 NY = 07:30 Halifax: beyond the 15-minute grace, so it is recorded as missed.
        assertEquals(MarkMissed("a1@$fri"), reconcile(listOf(occ(fri)), at(fri, 6, 30), zone = halifax).first())
    }

    @Test
    fun oneTimeAlarmInPastIsDisabled() {
        val oneTime = AlarmRecord("a1", AlarmSchedule(LocalTime.of(7, 0), emptySet(), fri), true)
        val actions = reconcile(emptyList(), at(fri, 9), alarms = listOf(oneTime))
        assertEquals(listOf(DisableOneTimeAlarm("a1")), actions)
    }

    @Test
    fun oneTimeAlarmMissedWhileOffIsMarkedAndDisabled() {
        val oneTime = AlarmRecord("a1", AlarmSchedule(LocalTime.of(7, 0), emptySet(), fri), true)
        val actions = reconcile(listOf(occ(fri)), at(fri, 9), alarms = listOf(oneTime))
        assertEquals(listOf(MarkMissed("a1@$fri"), DisableOneTimeAlarm("a1")), actions)
    }

    @Test
    fun reconcileIsIdempotent() {
        val first = reconcile(listOf(occ(fri)), at(fri, 6))
        val second = reconcile(listOf(occ(fri)), at(fri, 6))
        assertEquals(first, second)
        assertTrue(first.all { it is Schedule })
    }
}
