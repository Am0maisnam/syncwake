package app.syncwake.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import app.syncwake.MainActivity

/**
 * Thin wrapper over [AlarmManager]. Each occurrence has exactly one OS registration, identified
 * by its data URI, so re-scheduling (snooze, edits, time-zone changes) replaces rather than
 * duplicates it.
 *
 * Alarm rings use [AlarmManager.setAlarmClock]: the system treats these as the most critical
 * alarms, never defers them, and leaves Doze to deliver them.
 */
class AlarmScheduler(private val context: Context) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleRing(occurrenceId: String, triggerAtMillis: Long) {
        val operation = ringIntent(occurrenceId, PendingIntent.FLAG_UPDATE_CURRENT)!!
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), operation)
                return
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarm permission revoked while scheduling", e)
        }
        // Degraded but better than nothing; Smart Alarm Preparation tells the user to fix it.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    }

    fun cancelRing(occurrenceId: String) {
        ringIntent(occurrenceId, PendingIntent.FLAG_NO_CREATE)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /** True if an OS registration exists for this occurrence (they are all lost on reboot). */
    fun isRingRegistered(occurrenceId: String): Boolean =
        ringIntent(occurrenceId, PendingIntent.FLAG_NO_CREATE) != null

    /** Wake-Up Proof checks: exact, Doze-piercing, but not shown as the "next alarm" clock. */
    fun scheduleWakeProof(occurrenceId: String, action: String, atMillis: Long) {
        val operation = wakeProofIntent(occurrenceId, action, PendingIntent.FLAG_UPDATE_CURRENT)!!
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                return
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarm permission revoked while scheduling wake proof", e)
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    fun cancelWakeProof(occurrenceId: String) {
        for (action in listOf(AlarmReceiver.ACTION_WAKE_PROOF_CHECK, AlarmReceiver.ACTION_WAKE_PROOF_DEADLINE)) {
            wakeProofIntent(occurrenceId, action, PendingIntent.FLAG_NO_CREATE)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }

    private fun ringIntent(occurrenceId: String, flags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_RING)
            .setData(Uri.Builder().scheme("syncwake").authority("ring").appendPath(occurrenceId).build())
            .putExtra(AlarmReceiver.EXTRA_OCCURRENCE_ID, occurrenceId)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun wakeProofIntent(occurrenceId: String, action: String, flags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .setData(Uri.Builder().scheme("syncwake").authority("wakeproof").appendPath(action).appendPath(occurrenceId).build())
            .putExtra(AlarmReceiver.EXTRA_OCCURRENCE_ID, occurrenceId)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val TAG = "AlarmScheduler"
    }
}
