package app.syncwake.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.syncwake.MainActivity
import app.syncwake.R
import app.syncwake.alarm.AlarmReceiver
import app.syncwake.ring.AlarmRingingService
import app.syncwake.ui.ring.AlarmActivity
import app.syncwake.ui.ring.WakeProofActivity

object Notifications {
    const val CHANNEL_RINGING = "alarm_ringing"
    const val CHANNEL_WAKE_PROOF = "wake_proof"
    const val CHANNEL_STATUS = "alarm_status"

    const val ID_RINGING = 1
    private const val ID_BASE_STATUS = 1000
    private const val ID_BASE_WAKE_PROOF = 2000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                // Silent channel: the ringing service plays the alarm audio itself on the alarm stream.
                NotificationChannel(CHANNEL_RINGING, context.getString(R.string.channel_ringing), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_ringing_desc)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CHANNEL_WAKE_PROOF, context.getString(R.string.channel_wake_proof), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_wake_proof_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_status_desc)
                },
            ),
        )
    }

    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun ringing(context: Context, occurrenceId: String, label: String, canSnooze: Boolean): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, 1, AlarmActivity.intent(context, occurrenceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(label.ifBlank { context.getString(R.string.alarm_default_label) })
            .setContentText(context.getString(R.string.ringing_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            // "Dismiss" opens the challenge; there is deliberately no one-tap dismiss action.
            .addAction(0, context.getString(R.string.action_dismiss), fullScreen)
        if (canSnooze) {
            val snooze = PendingIntent.getService(
                context, 2, AlarmRingingService.snoozeIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.action_snooze), snooze)
        }
        return builder.build()
    }

    fun showSnoozed(context: Context, occurrenceId: String, label: String, untilText: String) =
        notifyStatus(context, occurrenceId, context.getString(R.string.snoozed_title, untilText), label)

    fun showMissed(context: Context, occurrenceId: String, label: String) =
        notifyStatus(context, occurrenceId, context.getString(R.string.missed_title), label)

    fun cancelStatus(context: Context, occurrenceId: String) =
        NotificationManagerCompat.from(context).cancel(ID_BASE_STATUS + idFor(occurrenceId))

    fun showWakeProof(context: Context, occurrenceId: String, label: String) {
        val open = PendingIntent.getActivity(
            context, 3, WakeProofActivity.intent(context, occurrenceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val confirm = PendingIntent.getBroadcast(
            context, 4,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_WAKE_PROOF_CONFIRM)
                .putExtra(AlarmReceiver.EXTRA_OCCURRENCE_ID, occurrenceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_WAKE_PROOF)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.wake_proof_title))
            .setContentText(label.ifBlank { context.getString(R.string.wake_proof_text) })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.wake_proof_confirm), confirm)
            .build()
        postSafely(context, ID_BASE_WAKE_PROOF + idFor(occurrenceId), notification)
    }

    fun cancelWakeProof(context: Context, occurrenceId: String) =
        NotificationManagerCompat.from(context).cancel(ID_BASE_WAKE_PROOF + idFor(occurrenceId))

    private fun notifyStatus(context: Context, occurrenceId: String, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 5, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(text.ifBlank { context.getString(R.string.alarm_default_label) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        postSafely(context, ID_BASE_STATUS + idFor(occurrenceId), notification)
    }

    private fun postSafely(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call; readiness reports it.
        }
    }

    private fun idFor(occurrenceId: String): Int = (occurrenceId.hashCode() and 0x7FFFFFFF) % 997
}
