package app.syncwake.domain.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReadinessTest {
    private val good = ReadinessSnapshot(
        hasUpcomingAlarm = true,
        upcomingAlarmRegisteredWithSystem = true,
        canScheduleExactAlarms = true,
        notificationsAllowed = true,
        alarmChannelEnabled = true,
        canUseFullScreenIntent = true,
        alarmVolume = 7,
        alarmVolumeMax = 7,
        ignoringBatteryOptimizations = true,
        fallbackAudioAvailable = true,
        customAudioAvailable = null,
    )

    @Test
    fun allGoodIsReady() {
        val report = AlarmReadiness.evaluate(good)
        assertTrue(report.ready)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun warningsDoNotBlock() {
        val report = AlarmReadiness.evaluate(
            good.copy(canUseFullScreenIntent = false, ignoringBatteryOptimizations = false, alarmVolume = 2, customAudioAvailable = false),
        )
        assertTrue(report.ready)
        assertEquals(4, report.issues.size)
    }

    @Test
    fun blockingIssuesAreListedFirst() {
        val report = AlarmReadiness.evaluate(good.copy(canUseFullScreenIntent = false, alarmVolume = 0, canScheduleExactAlarms = false))
        assertFalse(report.ready)
        assertTrue(report.issues.first().blocking)
        assertTrue(ReadinessIssue.ALARM_VOLUME_MUTED in report.issues)
        assertFalse(ReadinessIssue.ALARM_VOLUME_LOW in report.issues)
    }

    @Test
    fun unregisteredAlarmOnlyMattersWhenOneExists() {
        assertFalse(AlarmReadiness.evaluate(good.copy(upcomingAlarmRegisteredWithSystem = false)).ready)
        assertTrue(AlarmReadiness.evaluate(good.copy(hasUpcomingAlarm = false, upcomingAlarmRegisteredWithSystem = false)).ready)
    }

    @Test
    fun disabledChannelReportedOnlyWhenNotificationsAllowed() {
        val issues = AlarmReadiness.evaluate(good.copy(notificationsAllowed = false, alarmChannelEnabled = false)).issues
        assertEquals(listOf(ReadinessIssue.NOTIFICATIONS_BLOCKED), issues)
    }
}
