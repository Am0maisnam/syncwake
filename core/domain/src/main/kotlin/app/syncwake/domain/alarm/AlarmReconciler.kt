package app.syncwake.domain.alarm

import app.syncwake.domain.state.AlarmState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AlarmRecord(
    val id: String,
    val schedule: AlarmSchedule,
    val enabled: Boolean,
)

/** An occurrence that has not finished ringing yet. */
data class PendingOccurrence(
    val id: String,
    val alarmId: String,
    val localDate: LocalDate,
    val triggerAt: Instant,
    val state: AlarmState,
    /** Set while [AlarmState.SNOOZED]. */
    val snoozeUntil: Instant? = null,
    /** When it last started ringing; set for audible states. */
    val lastFiredAt: Instant? = null,
)

sealed interface ReconcileAction {
    /**
     * Create or update the occurrence as SCHEDULED and register it with the OS. Idempotent.
     * The platform layer must never reset an occurrence that already progressed past SCHEDULED
     * (e.g. a completed occurrence that becomes "next" again after flying west).
     */
    data class Schedule(val alarmId: String, val occurrenceId: String, val localDate: LocalDate, val triggerAt: Instant) : ReconcileAction
    /** Re-register a snoozed occurrence with the OS. */
    data class ScheduleSnooze(val occurrenceId: String, val at: Instant) : ReconcileAction
    /** Overdue but within the grace period: ring immediately. */
    data class RingNow(val occurrenceId: String) : ReconcileAction
    data class MarkMissed(val occurrenceId: String) : ReconcileAction
    /** No longer matches the alarm (edited, disabled, deleted): unregister and mark cancelled. */
    data class Cancel(val occurrenceId: String) : ReconcileAction
    /** A one-time alarm with nothing left to ring. */
    data class DisableOneTimeAlarm(val alarmId: String) : ReconcileAction
}

/**
 * Brings the OS alarm registrations in line with the database. Pure: it returns actions and the
 * platform layer applies them. Safe to run any number of times (app start, boot, package update,
 * time or time-zone change, exact-alarm permission grant, alarm edits).
 *
 * The OS loses all alarm registrations on reboot, so this is also how alarms survive restarts.
 */
object AlarmReconciler {

    /**
     * @param audibleInThisProcess occurrence ids the ringing service is playing right now; never
     *   touched. Any other occurrence recorded as audible was interrupted (process death or
     *   reboot), so it is rung again if recent or marked missed.
     */
    fun reconcile(
        alarms: List<AlarmRecord>,
        pending: List<PendingOccurrence>,
        now: Instant,
        zone: ZoneId,
        audibleInThisProcess: Set<String> = emptySet(),
        grace: Duration = OverduePolicy.DEFAULT_LATE_RING_GRACE,
    ): List<ReconcileAction> {
        val actions = mutableListOf<ReconcileAction>()
        val alarmsById = alarms.associateBy { it.id }
        val pendingByAlarm = pending.groupBy { it.alarmId }

        // Occurrences whose alarm no longer exists.
        for (occ in pending) {
            if (occ.alarmId !in alarmsById && occ.id !in audibleInThisProcess && occ.state.cancellable()) {
                actions += ReconcileAction.Cancel(occ.id)
            }
        }

        for (alarm in alarms) {
            val occurrences = pendingByAlarm[alarm.id].orEmpty()
            val expected = NextTriggerCalculator.next(alarm.schedule, now, zone)
            val expectedId = expected?.let { occurrenceId(alarm.id, it.localDate) }
            // An in-flight (snoozed/ringing) occurrence with the expected id must not be rescheduled.
            var expectedHandled = occurrences.any { it.id == expectedId && it.state != AlarmState.SCHEDULED }

            for (occ in occurrences) {
                if (occ.id in audibleInThisProcess) continue
                when (occ.state) {
                    AlarmState.RINGING, AlarmState.DISMISS_CHALLENGE, AlarmState.CHALLENGE_FAILED -> {
                        // Recorded as ringing, but nothing in this process is playing it: the
                        // process or device died mid-ring. Resume if recent, otherwise missed.
                        val firedAt = occ.lastFiredAt ?: occ.triggerAt
                        actions += overdue(occ.id, firedAt, now, grace)
                    }
                    AlarmState.SNOOZED -> {
                        val at = occ.snoozeUntil ?: occ.triggerAt
                        actions += when (OverduePolicy.decide(at, now, grace)) {
                            OverdueDecision.SCHEDULE -> ReconcileAction.ScheduleSnooze(occ.id, at)
                            OverdueDecision.RING_NOW -> ReconcileAction.RingNow(occ.id)
                            OverdueDecision.MISSED -> ReconcileAction.MarkMissed(occ.id)
                        }
                    }
                    AlarmState.SCHEDULED -> {
                        if (!alarm.enabled || !alarm.schedule.covers(occ.localDate)) {
                            actions += ReconcileAction.Cancel(occ.id)
                            continue
                        }
                        // Re-resolve in the current zone: a time-zone change can move it.
                        val trigger = NextTriggerCalculator.triggerFor(alarm.schedule, occ.localDate, zone)
                        when (OverduePolicy.decide(trigger, now, grace)) {
                            OverdueDecision.SCHEDULE -> {
                                if (expected != null && expected.localDate == occ.localDate) {
                                    actions += ReconcileAction.Schedule(alarm.id, occ.id, occ.localDate, trigger)
                                    expectedHandled = true
                                } else {
                                    // A later date than the next one (the alarm was edited to ring sooner).
                                    actions += ReconcileAction.Cancel(occ.id)
                                }
                            }
                            OverdueDecision.RING_NOW -> actions += ReconcileAction.RingNow(occ.id)
                            OverdueDecision.MISSED -> actions += ReconcileAction.MarkMissed(occ.id)
                        }
                    }
                    else -> Unit // completed states are not pending
                }
            }

            if (!alarm.enabled) continue
            if (expected != null) {
                if (!expectedHandled) {
                    val id = occurrenceId(alarm.id, expected.localDate)
                    actions += ReconcileAction.Schedule(alarm.id, id, expected.localDate, expected.triggerAt)
                }
            } else if (!alarm.schedule.isRecurring) {
                actions += ReconcileAction.DisableOneTimeAlarm(alarm.id)
            }
        }
        return actions
    }

    private fun overdue(occurrenceId: String, at: Instant, now: Instant, grace: Duration): ReconcileAction =
        if (OverduePolicy.decide(at, now, grace) == OverdueDecision.MISSED) ReconcileAction.MarkMissed(occurrenceId)
        else ReconcileAction.RingNow(occurrenceId)

    private fun AlarmState.cancellable() = this == AlarmState.SCHEDULED || this == AlarmState.SNOOZED

    private fun AlarmSchedule.covers(date: LocalDate): Boolean =
        if (isRecurring) date.dayOfWeek in repeatDays else date == oneTimeDate
}
