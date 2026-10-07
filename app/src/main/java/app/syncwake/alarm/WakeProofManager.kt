package app.syncwake.alarm

import android.content.Context
import app.syncwake.data.AlarmRepository
import app.syncwake.data.alarmState
import app.syncwake.domain.state.AlarmEvent
import app.syncwake.domain.state.AlarmState
import app.syncwake.domain.time.WallClock
import app.syncwake.notify.Notifications
import app.syncwake.settings.AppSettings

/**
 * Wake-Up Proof ("Still awake? 👀"). Smart Wake Verification (passive, on-device activity
 * scoring) is a later phase; until then — and whenever it cannot run — every completed alarm
 * gets a Wake-Up Proof check, which is the specified fallback.
 *
 * Flow: COMPLETED --(check time)--> WAKE_PROOF_REQUIRED --(confirm)--> CONFIRMED_AWAKE
 *                                                       --(ignored)--> RINGING again or WAKE_STATUS_UNKNOWN
 */
class WakeProofManager(
    private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val settings: AppSettings,
    private val clock: WallClock,
) {
    /** When to ask "Still awake?" for a challenge completed at [completedAt]; null if disabled. */
    fun checkTimeAfterCompletion(completedAt: Long): Long? {
        val policy = settings.current.wakeProofPolicy
        return if (policy.enabled) completedAt + policy.checkAfter.toMillis() else null
    }

    fun scheduleCheck(occurrenceId: String, checkAt: Long) =
        scheduler.scheduleWakeProof(occurrenceId, AlarmReceiver.ACTION_WAKE_PROOF_CHECK, checkAt)

    suspend fun onCheckDue(occurrenceId: String) {
        val occurrence = repository.occurrence(occurrenceId) ?: return
        if (occurrence.alarmState != AlarmState.COMPLETED) return
        val now = clock.now().toEpochMilli()
        val deadline = now + settings.current.wakeProofPolicy.answerWindow.toMillis()
        repository.transition(occurrenceId, AlarmEvent.RequireWakeProof, now) {
            it.copy(wakeProofCheckAt = null, wakeProofDeadlineAt = deadline)
        } ?: return
        Notifications.showWakeProof(context, occurrenceId, occurrence.alarmLabel)
        scheduler.scheduleWakeProof(occurrenceId, AlarmReceiver.ACTION_WAKE_PROOF_DEADLINE, deadline)
    }

    suspend fun onDeadline(occurrenceId: String) {
        val occurrence = repository.occurrence(occurrenceId) ?: return
        if (occurrence.alarmState != AlarmState.WAKE_PROOF_REQUIRED) return
        val now = clock.now().toEpochMilli()
        Notifications.cancelWakeProof(context, occurrenceId)
        if (settings.current.wakeProofPolicy.shouldReAlert(occurrence.wakeProofReAlerts)) {
            repository.transition(occurrenceId, AlarmEvent.WakeProofIgnoredReAlert, now) {
                it.copy(wakeProofReAlerts = it.wakeProofReAlerts + 1, wakeProofDeadlineAt = null, lastFiredAt = now)
            } ?: return
            // Ring again through AlarmManager (works from any context, including after reboot).
            scheduler.scheduleRing(occurrenceId, now)
        } else {
            repository.transition(occurrenceId, AlarmEvent.WakeProofIgnoredGiveUp, now) {
                it.copy(wakeProofDeadlineAt = null)
            }
        }
    }

    suspend fun confirm(occurrenceId: String): Boolean {
        val now = clock.now().toEpochMilli()
        val updated = repository.transition(occurrenceId, AlarmEvent.WakeProofConfirmed, now) {
            it.copy(confirmedAwakeAt = now, wakeProofDeadlineAt = null)
        }
        Notifications.cancelWakeProof(context, occurrenceId)
        scheduler.cancelWakeProof(occurrenceId)
        return updated != null
    }

    /** Re-registers pending checks after reboot and runs any that came due while the device was off. */
    suspend fun recover() {
        val now = clock.now().toEpochMilli()
        for (occ in repository.occurrencesIn(AlarmState.COMPLETED)) {
            val checkAt = occ.wakeProofCheckAt ?: continue
            if (checkAt <= now) onCheckDue(occ.occurrenceId)
            else scheduler.scheduleWakeProof(occ.occurrenceId, AlarmReceiver.ACTION_WAKE_PROOF_CHECK, checkAt)
        }
        for (occ in repository.occurrencesIn(AlarmState.WAKE_PROOF_REQUIRED)) {
            val deadline = occ.wakeProofDeadlineAt ?: continue
            if (deadline <= now) onDeadline(occ.occurrenceId)
            else scheduler.scheduleWakeProof(occ.occurrenceId, AlarmReceiver.ACTION_WAKE_PROOF_DEADLINE, deadline)
        }
    }
}
