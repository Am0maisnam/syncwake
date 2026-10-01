package app.syncwake.domain.alarm

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextTriggerCalculatorTest {
    private val ny = ZoneId.of("America/New_York")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val seven = LocalTime.of(7, 0)

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant()

    @Test
    fun oneTimeAlarmInFutureRingsOnItsDate() {
        val schedule = AlarmSchedule(seven, emptySet(), LocalDate.of(2026, 10, 2))
        val next = NextTriggerCalculator.next(schedule, at(ny, 2026, 10, 1, 22), ny)!!
        assertEquals(at(ny, 2026, 10, 2, 7), next.triggerAt)
        assertEquals(LocalDate.of(2026, 10, 2), next.localDate)
    }

    @Test
    fun oneTimeAlarmInPastReturnsNull() {
        val schedule = AlarmSchedule(seven, emptySet(), LocalDate.of(2026, 10, 1))
        assertNull(NextTriggerCalculator.next(schedule, at(ny, 2026, 10, 1, 7), ny))
    }

    @Test
    fun recurringAlarmSkipsToNextSelectedWeekday() {
        // 2026-10-02 is a Friday.
        val schedule = AlarmSchedule(seven, setOf(MONDAY, WEDNESDAY), null)
        val next = NextTriggerCalculator.next(schedule, at(ny, 2026, 10, 2, 6), ny)!!
        assertEquals(at(ny, 2026, 10, 5, 7), next.triggerAt) // Monday
    }

    @Test
    fun recurringAlarmLaterTodayRingsToday() {
        val schedule = AlarmSchedule(seven, setOf(FRIDAY), null)
        val next = NextTriggerCalculator.next(schedule, at(ny, 2026, 10, 2, 6, 59), ny)!!
        assertEquals(at(ny, 2026, 10, 2, 7), next.triggerAt)
    }

    @Test
    fun recurringAlarmExactlyAtTriggerMovesToNextWeek() {
        val schedule = AlarmSchedule(seven, setOf(FRIDAY), null)
        val next = NextTriggerCalculator.next(schedule, at(ny, 2026, 10, 2, 7), ny)!!
        assertEquals(at(ny, 2026, 10, 9, 7), next.triggerAt)
    }

    @Test
    fun floatingAlarmFollowsDeviceZoneAfterTravel() {
        val schedule = AlarmSchedule(seven, setOf(SATURDAY), null)
        val now = at(ny, 2026, 10, 2, 12)
        val inNy = NextTriggerCalculator.next(schedule, now, ny)!!
        val inKolkata = NextTriggerCalculator.next(schedule, now, kolkata)!!
        assertEquals(at(ny, 2026, 10, 3, 7), inNy.triggerAt)
        assertEquals(at(kolkata, 2026, 10, 3, 7), inKolkata.triggerAt)
    }

    @Test
    fun fixedAnchorIgnoresDeviceZone() {
        val schedule = AlarmSchedule(seven, setOf(SATURDAY), null, TimeAnchor.Fixed(ny))
        val now = at(ny, 2026, 10, 2, 12)
        assertEquals(
            NextTriggerCalculator.next(schedule, now, ny)!!.triggerAt,
            NextTriggerCalculator.next(schedule, now, kolkata)!!.triggerAt,
        )
    }

    @Test
    fun springForwardGapShiftsLater() {
        // 2026-03-08 02:30 does not exist in New York; clocks jump 02:00 -> 03:00.
        val schedule = AlarmSchedule(LocalTime.of(2, 30), emptySet(), LocalDate.of(2026, 3, 8))
        val next = NextTriggerCalculator.next(schedule, at(ny, 2026, 3, 7, 12), ny)!!
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, ny).toInstant(), next.triggerAt)
    }

    @Test
    fun fallBackOverlapRingsOnceAtEarlierOffset() {
        // 2026-11-01 01:30 happens twice in New York; use the first (EDT, UTC-4).
        val schedule = AlarmSchedule(LocalTime.of(1, 30), emptySet(), LocalDate.of(2026, 11, 1))
        val next = NextTriggerCalculator.next(schedule, at(ny, 2026, 10, 31, 12), ny)!!
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), next.triggerAt)
    }

    @Test
    fun defaultOneTimeDateIsTomorrowWhenTimeHasPassed() {
        assertEquals(
            LocalDate.of(2026, 10, 2),
            NextTriggerCalculator.defaultOneTimeDate(seven, at(ny, 2026, 10, 1, 22), ny),
        )
        assertEquals(
            LocalDate.of(2026, 10, 1),
            NextTriggerCalculator.defaultOneTimeDate(seven, at(ny, 2026, 10, 1, 6), ny),
        )
    }

    @Test
    fun midnightAlarm() {
        val schedule = AlarmSchedule(LocalTime.MIDNIGHT, setOf(SATURDAY), null)
        val next = NextTriggerCalculator.next(schedule, at(ny, 2026, 10, 2, 23, 59), ny)!!
        assertEquals(at(ny, 2026, 10, 3, 0), next.triggerAt)
    }

    @Test
    fun occurrenceIdIsDeterministic() {
        assertEquals("abc@2026-10-02", occurrenceId("abc", LocalDate.of(2026, 10, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneTimeAlarmRequiresDate() {
        AlarmSchedule(seven, emptySet(), null)
    }
}
