package app.syncwake

import android.content.Context
import android.os.UserManager
import app.syncwake.alarm.AlarmCoordinator
import app.syncwake.alarm.AlarmScheduler
import app.syncwake.alarm.WakeProofManager
import app.syncwake.data.AlarmRepository
import app.syncwake.data.SyncWakeDatabase
import app.syncwake.domain.time.SystemWallClock
import app.syncwake.domain.time.WallClock
import app.syncwake.readiness.ReadinessChecker
import app.syncwake.settings.AppSettings
import app.syncwake.social.SocialConfig
import app.syncwake.social.SocialRepository
import app.syncwake.social.SocialStore
import app.syncwake.social.WorkManagerSyncScheduler

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

    // Social state lives in credential-protected storage, which is unreadable before the first
    // unlock after a reboot. Alarms can ring in that window, so it must never be touched eagerly.
    val socialStore: SocialStore by lazy { SocialStore(appContext) }
    val social: SocialRepository by lazy {
        SocialRepository(appContext, SocialConfig.fromBuild, socialStore, repository, coordinator, clock)
    }

    fun isUserUnlocked(): Boolean = appContext.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    init {
        // Any state change of a shared alarm's occurrence (ringing, completed, awake...) is
        // uploaded as soon as there's a network. Personal alarms never leave the phone.
        repository.onTransition = {
            if (SocialConfig.fromBuild.isConfigured && isUserUnlocked() && social.signedIn) {
                WorkManagerSyncScheduler(appContext).requestNow()
            }
        }
    }

    companion object {
        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }
    }
}
