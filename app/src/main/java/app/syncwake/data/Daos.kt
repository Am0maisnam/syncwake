package app.syncwake.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE alarmId = :alarmId")
    suspend fun get(alarmId: String): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alarm: AlarmEntity): Long

    @Update
    suspend fun update(alarm: AlarmEntity)

    @Query("UPDATE alarms SET enabled = :enabled, updatedAt = :now WHERE alarmId = :alarmId")
    suspend fun setEnabled(alarmId: String, enabled: Boolean, now: Long)

    @Query("DELETE FROM alarms WHERE alarmId = :alarmId")
    suspend fun delete(alarmId: String)
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM occurrences WHERE occurrenceId = :id")
    suspend fun get(id: String): OccurrenceEntity?

    @Query("SELECT * FROM occurrences WHERE state IN (:states)")
    suspend fun getInStates(states: List<String>): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE state IN (:states) ORDER BY triggerAt LIMIT 1")
    fun observeEarliestInStates(states: List<String>): Flow<OccurrenceEntity?>

    @Query("SELECT * FROM occurrences WHERE state NOT IN ('SCHEDULED') AND triggerAt >= :since ORDER BY triggerAt DESC")
    fun observeHistory(since: Long): Flow<List<OccurrenceEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(occurrence: OccurrenceEntity): Long

    /** Only ever moves a still-SCHEDULED occurrence; never resurrects a finished one. */
    @Query(
        "UPDATE occurrences SET triggerAt = :triggerAt, alarmLabel = :label, updatedAt = :now " +
            "WHERE occurrenceId = :id AND state = 'SCHEDULED'",
    )
    suspend fun updateScheduled(id: String, triggerAt: Long, label: String, now: Long): Int

    @Update
    suspend fun update(occurrence: OccurrenceEntity)
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: OccurrenceEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: ChallengeAttemptEntity)

    @Query("SELECT * FROM occurrence_events WHERE occurrenceId = :occurrenceId ORDER BY elapsedRealtime, at")
    suspend fun eventsFor(occurrenceId: String): List<OccurrenceEventEntity>

    @Query("SELECT * FROM occurrence_events WHERE synced = 0 ORDER BY at, elapsedRealtime LIMIT :limit")
    suspend fun unsynced(limit: Int): List<OccurrenceEventEntity>

    @Query("UPDATE occurrence_events SET synced = 1 WHERE eventId IN (:eventIds)")
    suspend fun markSynced(eventIds: List<String>)
}
