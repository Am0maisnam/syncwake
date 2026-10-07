package app.syncwake.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AlarmEntity::class, OccurrenceEntity::class, OccurrenceEventEntity::class, ChallengeAttemptEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SyncWakeDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun eventDao(): EventDao

    companion object {
        private const val NAME = "syncwake.db"

        /**
         * The alarm database lives in device-protected storage so alarms can be rescheduled and
         * rung after a reboot before the user unlocks the phone (Direct Boot). It holds alarm
         * schedules and outcomes only; account and social data will live in credential-protected
         * storage. Schema changes must ship a Migration; destructive fallback is never enabled.
         */
        fun create(context: Context): SyncWakeDatabase {
            val storage = context.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(storage, SyncWakeDatabase::class.java, NAME).build()
        }
    }
}
