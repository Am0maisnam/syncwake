package app.syncwake.ui

import android.content.Context
import android.text.format.DateFormat
import app.syncwake.Graph
import app.syncwake.settings.TimeFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

object Formatting {
    /** The user's choice in Settings, or the phone's own 12/24-hour setting. */
    fun is24Hour(context: Context): Boolean = when (Graph.get(context).settings.current.timeFormat) {
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    }

    fun time(context: Context, time: LocalTime): String {
        val pattern = if (is24Hour(context)) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    fun time(context: Context, epochMillis: Long): String =
        time(context, Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime())

    fun dateTime(context: Context, epochMillis: Long): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date) + " " + time(context, epochMillis)
    }

    fun days(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "Weekdays"
        days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
        else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    fun until(from: Instant, to: Instant): String {
        val d = Duration.between(from, to).coerceAtLeast(Duration.ZERO)
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

private fun Duration.coerceAtLeast(min: Duration): Duration = if (this < min) min else this
