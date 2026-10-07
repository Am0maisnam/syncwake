package app.syncwake.domain.alarm

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One concrete future ring of an alarm. */
data class PlannedOccurrence(
    /** Local calendar date the occurrence belongs to, in the anchor zone. */
    val localDate: LocalDate,
    /** Authoritative trigger instant. */
    val triggerAt: Instant,
)

/**
 * Computes when an alarm should next ring. Pure and deterministic: all inputs are explicit so it
 * can be re-run after reboots, clock changes and time-zone changes.
 *
 * DST handling, using java.time's documented resolution rules:
 * - Spring-forward gap (e.g. 02:30 does not exist): rings at the shifted time (03:30).
 * - Fall-back overlap (e.g. 01:30 happens twice): rings once, at the earlier offset.
 */
object NextTriggerCalculator {

    /**
     * @param after occurrences at or before this instant are not returned.
     * @param deviceZone zone used for [TimeAnchor.Floating] alarms.
     * @return null when a one-time alarm's ring time has already passed.
     */
    fun next(schedule: AlarmSchedule, after: Instant, deviceZone: ZoneId): PlannedOccurrence? {
        val zone = zoneFor(schedule, deviceZone)
        if (!schedule.isRecurring) {
            val date = requireNotNull(schedule.oneTimeDate)
            val trigger = resolve(date, schedule, zone)
            return if (trigger.isAfter(after)) PlannedOccurrence(date, trigger) else null
        }
        val startDate = after.atZone(zone).toLocalDate()
        // 8 days covers "today" plus a full week, which always contains a repeat day.
        for (offset in 0L..7L) {
            val date = startDate.plusDays(offset)
            if (date.dayOfWeek !in schedule.repeatDays) continue
            val trigger = resolve(date, schedule, zone)
            if (trigger.isAfter(after)) return PlannedOccurrence(date, trigger)
        }
        error("No occurrence found within 8 days for $schedule")
    }

    /**
     * The first date on or after [today] at which [time] is still in the future. Used to choose the
     * date of a new one-time alarm ("7:00" set at 22:00 means tomorrow).
     */
    fun defaultOneTimeDate(
        time: java.time.LocalTime,
        now: Instant,
        zone: ZoneId,
    ): LocalDate {
        val today = now.atZone(zone).toLocalDate()
        val todayTrigger = ZonedDateTime.ofLocal(LocalDateTime.of(today, time), zone, null).toInstant()
        return if (todayTrigger.isAfter(now)) today else today.plusDays(1)
    }

    /** The trigger instant of the occurrence on [date], resolved in the current zone. */
    fun triggerFor(schedule: AlarmSchedule, date: LocalDate, deviceZone: ZoneId): Instant =
        resolve(date, schedule, zoneFor(schedule, deviceZone))

    private fun zoneFor(schedule: AlarmSchedule, deviceZone: ZoneId): ZoneId = when (val anchor = schedule.anchor) {
        TimeAnchor.Floating -> deviceZone
        is TimeAnchor.Fixed -> anchor.zone
    }

    private fun resolve(date: LocalDate, schedule: AlarmSchedule, zone: ZoneId): Instant =
        ZonedDateTime.ofLocal(LocalDateTime.of(date, schedule.time), zone, null).toInstant()
}

/**
 * Stable, device-independent identifier for one occurrence of an alarm. Every participant's device
 * derives the same id for the same morning, which is what lets a synchronized alarm be reconciled
 * across devices and makes completion events idempotent.
 */
fun occurrenceId(alarmId: String, localDate: LocalDate): String = "$alarmId@$localDate"
