package app.syncwake.domain.readiness

/**
 * Snapshot of device conditions that affect whether the next alarm will ring properly. Collected
 * by the platform layer; evaluated here so the rules are testable.
 */
data class ReadinessSnapshot(
    val hasUpcomingAlarm: Boolean,
    /** The OS reports our next alarm clock at the time we expect. */
    val upcomingAlarmRegisteredWithSystem: Boolean,
    val canScheduleExactAlarms: Boolean,
    val notificationsAllowed: Boolean,
    val alarmChannelEnabled: Boolean,
    val canUseFullScreenIntent: Boolean,
    val alarmVolume: Int,
    val alarmVolumeMax: Int,
    val ignoringBatteryOptimizations: Boolean,
    val fallbackAudioAvailable: Boolean,
    /** null when the alarm uses the bundled sound. */
    val customAudioAvailable: Boolean?,
)

enum class ReadinessIssue(val blocking: Boolean, val message: String) {
    NOT_REGISTERED(true, "Your next alarm isn't registered with the system. Open SyncWake to reschedule it."),
    EXACT_ALARM_DENIED(true, "SyncWake isn't allowed to set exact alarms, so your alarm could ring late."),
    NOTIFICATIONS_BLOCKED(true, "Notifications are off, so the alarm screen may not appear."),
    ALARM_CHANNEL_DISABLED(true, "The \"Alarms\" notification category is turned off."),
    FALLBACK_AUDIO_MISSING(true, "The built-in alarm sound is unavailable. Reinstall SyncWake."),
    ALARM_VOLUME_MUTED(true, "Alarm volume is at zero."),
    FULL_SCREEN_DENIED(false, "SyncWake can't show the full-screen alarm on your lock screen; you'll get a notification instead."),
    ALARM_VOLUME_LOW(false, "Alarm volume is low."),
    BATTERY_OPTIMIZED(false, "Battery optimization is on. Alarms still ring, but turning it off for SyncWake improves reliability on some phones."),
    CUSTOM_AUDIO_MISSING(false, "Your custom alarm sound isn't available; the built-in sound will play instead."),
}

data class ReadinessReport(val issues: List<ReadinessIssue>) {
    val ready: Boolean get() = issues.none { it.blocking }
}

object AlarmReadiness {
    /** Volume at or below this fraction of max is reported as low. */
    const val LOW_VOLUME_FRACTION = 0.3

    fun evaluate(s: ReadinessSnapshot): ReadinessReport {
        val issues = buildList {
            if (s.hasUpcomingAlarm && !s.upcomingAlarmRegisteredWithSystem) add(ReadinessIssue.NOT_REGISTERED)
            if (!s.canScheduleExactAlarms) add(ReadinessIssue.EXACT_ALARM_DENIED)
            if (!s.notificationsAllowed) add(ReadinessIssue.NOTIFICATIONS_BLOCKED)
            else if (!s.alarmChannelEnabled) add(ReadinessIssue.ALARM_CHANNEL_DISABLED)
            if (!s.fallbackAudioAvailable) add(ReadinessIssue.FALLBACK_AUDIO_MISSING)
            when {
                s.alarmVolume <= 0 -> add(ReadinessIssue.ALARM_VOLUME_MUTED)
                s.alarmVolumeMax > 0 && s.alarmVolume.toDouble() / s.alarmVolumeMax <= LOW_VOLUME_FRACTION ->
                    add(ReadinessIssue.ALARM_VOLUME_LOW)
            }
            if (!s.canUseFullScreenIntent) add(ReadinessIssue.FULL_SCREEN_DENIED)
            if (!s.ignoringBatteryOptimizations) add(ReadinessIssue.BATTERY_OPTIMIZED)
            if (s.customAudioAvailable == false) add(ReadinessIssue.CUSTOM_AUDIO_MISSING)
        }
        return ReadinessReport(issues.sortedByDescending { it.blocking })
    }
}
