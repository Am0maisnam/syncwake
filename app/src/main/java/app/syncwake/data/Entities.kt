package app.syncwake.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * All timestamps are epoch milliseconds (UTC). Local dates are ISO-8601 strings. Display
 * formatting happens in the UI layer only.
 */
@Entity(tableName = "alarms", indices = [Index(value = ["alarmId"], unique = true)])
data class AlarmEntity(
    /** Small integer used for PendingIntent request codes. */
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    /** Stable UUID shared with the backend and other participants once social sync exists. */
    val alarmId: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    /** Bit 0 = Monday ... bit 6 = Sunday. 0 means a one-time alarm. */
    val repeatDaysMask: Int,
    val oneTimeDate: String?,
    /** null = floating (device zone); otherwise an IANA zone id. */
    val anchorZone: String?,
    val enabled: Boolean,
    val vibrate: Boolean,
    /** null = bundled sound. */
    val soundUri: String?,
    val snoozeMinutes: Int,
    val maxSnoozes: Int,
    val ringDurationMinutes: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "occurrences",
    indices = [Index("alarmId"), Index("state"), Index("triggerAt")],
)
data class OccurrenceEntity(
    /** Deterministic: "<alarmId>@<localDate>" (see `occurrenceId` in the domain module). */
    @PrimaryKey val occurrenceId: String,
    val alarmId: String,
    /** Label snapshot so history stays readable after the alarm is edited or deleted. */
    val alarmLabel: String,
    val localDate: String,
    val triggerAt: Long,
    /** [app.syncwake.domain.state.AlarmState] name. */
    val state: String,
    val snoozeCount: Int = 0,
    val snoozeUntil: Long? = null,
    val lastFiredAt: Long? = null,
    val challengeFailures: Int = 0,
    val completedAt: Long? = null,
    val confirmedAwakeAt: Long? = null,
    val wakeProofCheckAt: Long? = null,
    val wakeProofDeadlineAt: Long? = null,
    val wakeProofReAlerts: Int = 0,
    val updatedAt: Long,
)

/**
 * Append-only log of state transitions. Each row has a unique [eventId], which makes it the
 * idempotent outbox for server sync in the social phase.
 */
@Entity(tableName = "occurrence_events", indices = [Index("occurrenceId"), Index("synced")])
data class OccurrenceEventEntity(
    @PrimaryKey val eventId: String,
    val occurrenceId: String,
    val fromState: String,
    val toState: String,
    val event: String,
    /** Wall-clock time, for display and server plausibility checks. */
    val at: Long,
    /** Monotonic time since boot, for ordering events within one boot session. */
    val elapsedRealtime: Long,
    val synced: Boolean = false,
)

@Entity(tableName = "challenge_attempts", indices = [Index("occurrenceId")])
data class ChallengeAttemptEntity(
    @PrimaryKey val attemptId: String,
    val occurrenceId: String,
    val startedAt: Long,
    val durationMillis: Long,
    /** SUCCEEDED, WRONG_CODE, TIMED_OUT or ABANDONED. */
    val outcome: String,
    val correctChars: Int,
    val wrongChars: Int,
    val escalationLevel: Int,
)
