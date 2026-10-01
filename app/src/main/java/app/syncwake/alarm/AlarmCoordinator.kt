package app.syncwake.alarm

import android.content.Context
import android.util.Log
import app.syncwake.data.AlarmEntity
import app.syncwake.data.AlarmRepository
import app.syncwake.data.toPending
import app.syncwake.data.toRecord
import app.syncwake.domain.alarm.AlarmReconciler
import app.syncwake.domain.alarm.ReconcileAction
import app.syncwake.domain.state.AlarmEvent
import app.syncwake.domain.time.WallClock
import app.syncwake.notify.Notifications
import app.syncwake.ring.RingingRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies [AlarmReconciler] decisions to the database and AlarmManager. Every path that can
 * affect scheduling (boot, package update, time/zone change, permission grant, app start, alarm
 * edits) ends here, so there is exactly one implementation of "make the OS match the database".
 */
class AlarmCoordinator(
    private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val wakeProof: WakeProofManager,
    private val clock: WallClock,
) {
    private val mutex = Mutex()

    suspend fun reconcile() = mutex.withLock {
        val now = clock.now()
        val alarms = repository.alarms()
        val actions = AlarmReconciler.reconcile(
            alarms = alarms.map { it.toRecord() },
            pending = repository.pendingOccurrences().map { it.toPending() },
            now = now,
            zone = clock.zone(),
            audibleInThisProcess = RingingRegistry.audibleOccurrenceIds,
        )
        val byId = alarms.associateBy { it.alarmId }
        for (action in actions) {
            try {
                apply(action, byId, now.toEpochMilli())
            } catch (e: Exception) {
                // One bad row must not prevent the remaining alarms from being scheduled.
                Log.e(TAG, "Failed to apply $action", e)
            }
        }
        wakeProof.recover()
    }

    suspend fun saveAlarm(alarm: AlarmEntity) {
        repository.saveAlarm(alarm)
        reconcile()
    }

    suspend fun setEnabled(alarmId: String, enabled: Boolean) {
        repository.setEnabled(alarmId, enabled, clock.now().toEpochMilli())
        reconcile()
    }

    suspend fun deleteAlarm(alarmId: String) {
        repository.deleteAlarm(alarmId)
        reconcile()
    }

    private suspend fun apply(action: ReconcileAction, alarms: Map<String, AlarmEntity>, now: Long) {
        when (action) {
            is ReconcileAction.Schedule -> {
                val label = alarms[action.alarmId]?.label.orEmpty()
                val trigger = action.triggerAt.toEpochMilli()
                if (repository.upsertScheduled(action.alarmId, action.occurrenceId, action.localDate, trigger, label, now)) {
                    scheduler.scheduleRing(action.occurrenceId, trigger)
                }
            }
            is ReconcileAction.ScheduleSnooze -> scheduler.scheduleRing(action.occurrenceId, action.at.toEpochMilli())
            // Re-fire through AlarmManager so the ring always starts from an exact-alarm broadcast,
            // which is what permits starting the foreground ringing service.
            is ReconcileAction.RingNow -> scheduler.scheduleRing(action.occurrenceId, now)
            is ReconcileAction.MarkMissed -> {
                scheduler.cancelRing(action.occurrenceId)
                repository.transition(action.occurrenceId, AlarmEvent.MarkMissed, now)?.let {
                    Notifications.showMissed(context, it.occurrenceId, it.alarmLabel)
                }
            }
            is ReconcileAction.Cancel -> {
                scheduler.cancelRing(action.occurrenceId)
                repository.transition(action.occurrenceId, AlarmEvent.Cancel, now)
                Notifications.cancelStatus(context, action.occurrenceId)
            }
            is ReconcileAction.DisableOneTimeAlarm -> repository.setEnabled(action.alarmId, false, now)
        }
    }

    private companion object {
        const val TAG = "AlarmCoordinator"
    }
}
