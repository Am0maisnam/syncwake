package app.syncwake.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.syncwake.Graph
import app.syncwake.ring.AlarmRingingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private fun BroadcastReceiver.runAsync(tag: String, block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e(tag, "Receiver work failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** Exact-alarm deliveries. Direct-Boot aware so alarms ring before the first unlock. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
        when (intent.action) {
            // Start the foreground service immediately: no I/O before this point.
            ACTION_RING -> AlarmRingingService.fire(context, occurrenceId)
            ACTION_WAKE_PROOF_CHECK -> runAsync(TAG) { Graph.get(context).wakeProof.onCheckDue(occurrenceId) }
            ACTION_WAKE_PROOF_DEADLINE -> runAsync(TAG) { Graph.get(context).wakeProof.onDeadline(occurrenceId) }
            ACTION_WAKE_PROOF_CONFIRM -> runAsync(TAG) { Graph.get(context).wakeProof.confirm(occurrenceId) }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_RING = "app.syncwake.action.RING"
        const val ACTION_WAKE_PROOF_CHECK = "app.syncwake.action.WAKE_PROOF_CHECK"
        const val ACTION_WAKE_PROOF_DEADLINE = "app.syncwake.action.WAKE_PROOF_DEADLINE"
        const val ACTION_WAKE_PROOF_CONFIRM = "app.syncwake.action.WAKE_PROOF_CONFIRM"
        const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    }
}

/**
 * Re-registers every alarm when the OS may have dropped or shifted them: reboot (including the
 * locked, pre-unlock boot phase), app update, manual clock change, time-zone change, and exact
 * alarm permission being granted.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runAsync(TAG) { Graph.get(context).coordinator.reconcile() }
        }
    }

    private companion object {
        const val TAG = "SystemEventReceiver"
    }
}
