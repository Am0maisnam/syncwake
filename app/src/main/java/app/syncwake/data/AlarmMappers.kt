package app.syncwake.data

import app.syncwake.domain.alarm.AlarmRecord
import app.syncwake.domain.alarm.AlarmSchedule
import app.syncwake.domain.alarm.PendingOccurrence
import app.syncwake.domain.alarm.SnoozePolicy
import app.syncwake.domain.alarm.TimeAnchor
import app.syncwake.domain.state.AlarmState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { mask, d -> mask or (1 shl (d.value - 1)) }

fun maskToDays(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()

val AlarmEntity.schedule: AlarmSchedule
    get() = AlarmSchedule(
        time = LocalTime.of(hour, minute),
        repeatDays = maskToDays(repeatDaysMask),
        oneTimeDate = oneTimeDate?.let(LocalDate::parse),
        anchor = anchorZone?.let { TimeAnchor.Fixed(ZoneId.of(it)) } ?: TimeAnchor.Floating,
    )

val AlarmEntity.snoozePolicy: SnoozePolicy
    get() = SnoozePolicy(snoozeMinutes.coerceIn(1, 30), maxSnoozes.coerceIn(0, 10))

fun AlarmEntity.toRecord() = AlarmRecord(alarmId, schedule, enabled)

val OccurrenceEntity.alarmState: AlarmState get() = AlarmState.valueOf(state)

fun OccurrenceEntity.toPending() = PendingOccurrence(
    id = occurrenceId,
    alarmId = alarmId,
    localDate = LocalDate.parse(localDate),
    triggerAt = Instant.ofEpochMilli(triggerAt),
    state = alarmState,
    snoozeUntil = snoozeUntil?.let(Instant::ofEpochMilli),
    lastFiredAt = lastFiredAt?.let(Instant::ofEpochMilli),
)

/** States in which an occurrence still needs the alarm machinery (scheduling or ringing). */
val PENDING_STATES: List<String> = listOf(
    AlarmState.SCHEDULED, AlarmState.SNOOZED, AlarmState.RINGING,
    AlarmState.DISMISS_CHALLENGE, AlarmState.CHALLENGE_FAILED,
).map { it.name }

val UPCOMING_STATES: List<String> = listOf(AlarmState.SCHEDULED, AlarmState.SNOOZED).map { it.name }
