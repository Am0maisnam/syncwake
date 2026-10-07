package app.syncwake.domain.alarm

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * How an alarm's local time is anchored.
 *
 * - [Floating]: "7:00 wherever I am". Resolved in the device's current zone, so it follows the
 *   user when they travel. This is how personal alarms behave in system clock apps.
 * - [Fixed]: "7:00 in America/New_York". Resolved in a specific zone; used by synchronized
 *   alarms where every participant must ring at the same instant.
 */
sealed interface TimeAnchor {
    data object Floating : TimeAnchor
    data class Fixed(val zone: ZoneId) : TimeAnchor
}

/**
 * The schedule-relevant part of an alarm. Presentation and sound settings live elsewhere.
 *
 * @property repeatDays empty for a one-time alarm.
 * @property oneTimeDate the local date a one-time alarm rings on. Ignored for recurring alarms.
 */
data class AlarmSchedule(
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek>,
    val oneTimeDate: LocalDate?,
    val anchor: TimeAnchor = TimeAnchor.Floating,
) {
    val isRecurring: Boolean get() = repeatDays.isNotEmpty()

    init {
        require(isRecurring || oneTimeDate != null) { "A one-time alarm needs a date" }
    }
}

const val ALARM_LABEL_MAX_LENGTH = 30
