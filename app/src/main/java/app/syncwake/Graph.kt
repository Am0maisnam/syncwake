package app.syncwake

import android.content.Context
import app.syncwake.alarm.AlarmCoordinator
import app.syncwake.alarm.AlarmScheduler
import app.syncwake.alarm.WakeProofManager
import app.syncwake.data.AlarmRepository
import app.syncwake.data.SyncWakeDatabase
import app.syncwake.domain.time.SystemWallClock
import app.syncwake.domain.time.WallClock
import app.syncwake.readiness.ReadinessChecker
import app.syncwake.settings.AppSettings

/**
 * Manual dependency container. Everything here is safe to create during Direct Boot: it only
 * touches device-protected storage.
 */
class Graph private constructor(context: Context) {
    val appContext: Context = context.applicationContext
    val clock: WallClock = SystemWallClock
    val database: SyncWakeDatabase = SyncWakeDatabase.create(appContext)
    val repository = AlarmRepository(database)
    val scheduler = AlarmScheduler(appContext)
    val settings = AppSettings(appContext)
    val wakeProof = WakeProofManager(appContext, repository, scheduler, settings, clock)
    val coordinator = AlarmCoordinator(appContext, repository, scheduler, wakeProof, clock)
    val readiness = ReadinessChecker(appContext, repository, scheduler)

    companion object {
        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }
    }
}
