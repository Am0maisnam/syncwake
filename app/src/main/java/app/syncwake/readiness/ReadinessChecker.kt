package app.syncwake.readiness

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import app.syncwake.R
import app.syncwake.alarm.AlarmScheduler
import app.syncwake.data.AlarmRepository
import app.syncwake.domain.readiness.AlarmReadiness
import app.syncwake.domain.readiness.ReadinessReport
import app.syncwake.domain.readiness.ReadinessSnapshot
import app.syncwake.notify.Notifications

/** Collects device state for Smart Alarm Preparation; the rules live in the domain module. */
class ReadinessChecker(
    private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
) {
    suspend fun check(): ReadinessReport = AlarmReadiness.evaluate(snapshot())

    suspend fun snapshot(): ReadinessSnapshot {
        val next = repository.upcomingOccurrences().minByOrNull { it.triggerAt }
        val nextAlarm = next?.let { repository.alarm(it.alarmId) }
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(Notifications.CHANNEL_RINGING)
        return ReadinessSnapshot(
            hasUpcomingAlarm = next != null,
            upcomingAlarmRegisteredWithSystem = next != null && scheduler.isRingRegistered(next.occurrenceId),
            canScheduleExactAlarms = scheduler.canScheduleExactAlarms(),
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            alarmChannelEnabled = channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE,
            canUseFullScreenIntent = Notifications.canUseFullScreenIntent(context),
            alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
            alarmVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            fallbackAudioAvailable = fallbackAudioAvailable(),
            customAudioAvailable = nextAlarm?.soundUri?.let { canOpen(Uri.parse(it)) },
        )
    }

    private fun fallbackAudioAvailable(): Boolean = try {
        context.resources.openRawResourceFd(R.raw.fallback_alarm).use { it.length > 0 }
    } catch (_: Exception) {
        false
    }

    private fun canOpen(uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }
}
