package app.syncwake.data

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import app.syncwake.domain.state.AlarmEvent
import app.syncwake.domain.state.AlarmState
import app.syncwake.domain.state.AlarmStateMachine
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class AlarmRepository(private val db: SyncWakeDatabase) {
    /** Called after every committed state transition (used to trigger social sync). */
    @Volatile
    var onTransition: (() -> Unit)? = null

    private val alarms = db.alarmDao()
    private val occurrences = db.occurrenceDao()
    private val events = db.eventDao()

    fun observeAlarms(): Flow<List<AlarmEntity>> = alarms.observeAll()
    fun observeNextUpcoming(): Flow<OccurrenceEntity?> = occurrences.observeEarliestInStates(UPCOMING_STATES)
    fun observeHistory(sinceMillis: Long): Flow<List<OccurrenceEntity>> = occurrences.observeHistory(sinceMillis)

    suspend fun alarms(): List<AlarmEntity> = alarms.getAll()
    suspend fun alarm(alarmId: String): AlarmEntity? = alarms.get(alarmId)
    suspend fun occurrence(id: String): OccurrenceEntity? = occurrences.get(id)
    suspend fun pendingOccurrences(): List<OccurrenceEntity> = occurrences.getInStates(PENDING_STATES)
    suspend fun occurrencesIn(vararg states: AlarmState): List<OccurrenceEntity> =
        occurrences.getInStates(states.map { it.name })
    suspend fun upcomingOccurrences(): List<OccurrenceEntity> = occurrences.getInStates(UPCOMING_STATES)

    suspend fun saveAlarm(alarm: AlarmEntity) {
        if (alarm.localId == 0L) alarms.insert(alarm) else alarms.update(alarm)
    }

    suspend fun setEnabled(alarmId: String, enabled: Boolean, now: Long) = alarms.setEnabled(alarmId, enabled, now)
    suspend fun deleteAlarm(alarmId: String) = alarms.delete(alarmId)

    /**
     * Inserts the occurrence as SCHEDULED, or moves its trigger if it is still SCHEDULED.
     * @return true if the occurrence is SCHEDULED afterwards (and so should be registered with the OS).
     */
    suspend fun upsertScheduled(
        alarmId: String,
        occurrenceId: String,
        localDate: LocalDate,
        triggerAt: Long,
        label: String,
        now: Long,
    ): Boolean = db.withTransaction {
        val inserted = occurrences.insertIgnore(
            OccurrenceEntity(
                occurrenceId = occurrenceId,
                alarmId = alarmId,
                alarmLabel = label,
                localDate = localDate.toString(),
                triggerAt = triggerAt,
                state = AlarmState.SCHEDULED.name,
                updatedAt = now,
            ),
        )
        inserted != -1L || occurrences.updateScheduled(occurrenceId, triggerAt, label, now) > 0
    }

    /**
     * Applies [event] through the state machine and records it in the event log, atomically.
     * [mutate] can set related fields on the new row. Returns null (and changes nothing) if the
     * occurrence does not exist or the transition is illegal from its current state.
     */
    suspend fun transition(
        occurrenceId: String,
        event: AlarmEvent,
        now: Long,
        mutate: (OccurrenceEntity) -> OccurrenceEntity = { it },
    ): OccurrenceEntity? = transitionInternal(occurrenceId, event, now, mutate)?.also {
        try {
            onTransition?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "onTransition hook failed", e)
        }
    }

    private suspend fun transitionInternal(
        occurrenceId: String,
        event: AlarmEvent,
        now: Long,
        mutate: (OccurrenceEntity) -> OccurrenceEntity,
    ): OccurrenceEntity? = db.withTransaction {
        val current = occurrences.get(occurrenceId) ?: return@withTransaction null
        val from = current.alarmState
        if (!AlarmStateMachine.canTransition(from, event)) {
            Log.w(TAG, "Ignoring $event for $occurrenceId in $from")
            return@withTransaction null
        }
        val to = AlarmStateMachine.transition(from, event)
        val updated = mutate(current.copy(state = to.name, updatedAt = now))
        occurrences.update(updated)
        events.insert(
            OccurrenceEventEntity(
                eventId = UUID.randomUUID().toString(),
                occurrenceId = occurrenceId,
                fromState = from.name,
                toState = to.name,
                event = event.toString(),
                at = now,
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )
        updated
    }

    suspend fun recordAttempt(attempt: ChallengeAttemptEntity) = events.insertAttempt(attempt)

    suspend fun eventsFor(occurrenceId: String) = events.eventsFor(occurrenceId)

    suspend fun unsyncedEvents(limit: Int): List<OccurrenceEventEntity> = events.unsynced(limit)

    suspend fun markEventsSynced(eventIds: List<String>) {
        if (eventIds.isNotEmpty()) events.markSynced(eventIds)
    }

    private companion object {
        const val TAG = "AlarmRepository"
    }
}
