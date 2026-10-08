package app.syncwake.social

import android.app.Activity
import android.content.Context
import android.util.Log
import app.syncwake.BuildConfig
import app.syncwake.alarm.AlarmCoordinator
import app.syncwake.data.AlarmEntity
import app.syncwake.data.AlarmRepository
import app.syncwake.domain.alarm.RingPolicy
import app.syncwake.domain.social.PublicStatusMapper
import app.syncwake.domain.social.SocialEvent
import app.syncwake.domain.state.AlarmState
import app.syncwake.domain.time.WallClock
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Everything "with friends". Shared alarms are ordinary local alarms (same id as on the server,
 * fixed time zone) so they ring exactly like personal ones, offline included. This class only
 * keeps them in sync and uploads the high-level status of each morning.
 */
class SocialRepository(
    private val context: Context,
    val config: SocialConfig,
    private val store: SocialStore,
    private val alarms: AlarmRepository,
    private val coordinator: AlarmCoordinator,
    private val clock: WallClock,
    private val api: ApiClient = ApiClient(config.apiBaseUrl, { store.account.value?.token }),
    private val scheduler: SyncScheduler = WorkManagerSyncScheduler(context),
) {
    val account: StateFlow<Account?> = store.account
    val shared: StateFlow<Map<String, SharedInfo>> = store.shared
    val signedIn: Boolean get() = store.account.value != null

    private val _liveChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Alarm ids whose friends' status changed (from push); open screens refresh on these. */
    val liveChanges: SharedFlow<String> = _liveChanges

    // ---- Account ------------------------------------------------------------------------------

    suspend fun signIn(activity: Activity) {
        check(config.isConfigured) { "Friends features aren't set up in this build" }
        val idToken = GoogleSignIn.idToken(activity, config.googleServerClientId)
        val result = guarded { api.signInWithGoogle(idToken, store.deviceId, BuildConfig.VERSION_NAME) }
        store.saveAccount(Account(result.userId, result.displayName, result.token))
        registerPushTokenQuietly()
        scheduler.schedulePeriodic()
        scheduler.requestNow()
    }

    /** Shared alarms stay on this phone as personal alarms; they just stop syncing. */
    suspend fun signOut() {
        try {
            api.logout()
        } catch (_: IOException) {
            // Offline: the session simply expires on the server.
        }
        store.clearAccount()
        scheduler.cancel()
    }

    suspend fun onNewPushToken(token: String) {
        store.pushToken = token
        if (signedIn) {
            try {
                guarded { api.registerPushToken(token) }
            } catch (e: IOException) {
                Log.w(TAG, "push token registration failed; will retry on next sign-in", e)
            }
        }
    }

    private suspend fun registerPushTokenQuietly() {
        if (!config.ensureFirebase(context)) return
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            onNewPushToken(token)
        } catch (e: Exception) {
            Log.w(TAG, "FCM token unavailable", e)
        }
    }

    fun notifyLiveChange(alarmId: String) {
        _liveChanges.tryEmit(alarmId)
    }

    // ---- Shared alarms -----------------------------------------------------------------------

    /** Creates [local] on the server and saves it here under the server's id. */
    suspend fun createShared(local: AlarmEntity): AlarmEntity {
        val zone = local.anchorZone ?: clock.zone().id
        val remote = guarded { api.createAlarm(local.toInput(zone)) }
        val saved = local.copy(localId = 0, alarmId = remote.id, anchorZone = remote.zoneId, enabled = true)
        alarms.saveAlarm(saved)
        store.setShared(remote.id, remote.toInfo())
        coordinator.reconcile()
        return saved
    }

    /** Owner edit: the server is updated first so everyone gets the same schedule. */
    suspend fun updateShared(local: AlarmEntity) {
        val info = store.shared.value[local.alarmId] ?: error("Not a shared alarm")
        check(info.isOwner) { "Only the owner can change a shared alarm" }
        val remote = try {
            guarded { api.updateAlarm(local.alarmId, local.toInput(local.anchorZone ?: clock.zone().id), info.version) }
        } catch (e: ApiException) {
            if (e.code == "version_conflict") pullAlarms()
            throw e
        }
        alarms.saveAlarm(local.copy(anchorZone = remote.zoneId))
        store.setShared(remote.id, remote.toInfo())
        coordinator.reconcile()
    }

    /** Owner: cancels it for everyone. Member: leaves it. Either way it's removed from this phone. */
    suspend fun deleteOrLeave(alarmId: String) {
        val info = store.shared.value[alarmId]
        if (info != null) guarded { if (info.isOwner) api.deleteAlarm(alarmId) else api.leave(alarmId) }
        store.removeShared(alarmId)
        coordinator.deleteAlarm(alarmId)
    }

    suspend fun join(code: String): RemoteAlarm {
        val remote = guarded { api.acceptInvite(code) }
        upsertLocal(remote)
        store.setShared(remote.id, remote.toInfo())
        coordinator.reconcile()
        return remote
    }

    suspend fun invite(alarmId: String): Invite = guarded { api.createInvite(alarmId) }

    suspend fun details(alarmId: String): RemoteAlarm {
        val remote = guarded { api.getAlarm(alarmId) }
        store.setShared(remote.id, remote.toInfo())
        return remote
    }

    suspend fun removeParticipant(alarmId: String, userId: String): RemoteAlarm {
        guarded { api.removeParticipant(alarmId, userId) }
        return details(alarmId)
    }

    suspend fun occurrenceStatus(occurrenceId: String): OccurrenceStatus = guarded { api.occurrenceStatus(occurrenceId) }

    fun openLive(alarmId: String, onStatus: (OccurrenceStatus) -> Unit) =
        api.openLive(alarmId, onStatus) { _liveChanges.tryEmit(alarmId) }

    // ---- Sync ---------------------------------------------------------------------------------

    /** Upload pending statuses first (they're time-sensitive), then refresh shared alarms. */
    suspend fun syncNow() {
        if (!signedIn) return
        uploadEvents()
        pullAlarms()
    }

    /** Makes local shared alarms match the server: new, changed, cancelled, left or removed. */
    suspend fun pullAlarms() {
        val active = guarded { api.listAlarms() }.filter { it.isActive }
        val activeIds = active.map { it.id }.toSet()
        for (remote in active) upsertLocal(remote)
        for (gone in store.shared.value.keys - activeIds) {
            Log.i(TAG, "Shared alarm $gone was cancelled or you were removed; deleting locally")
            alarms.deleteAlarm(gone)
        }
        store.replaceShared(active.associate { it.id to it.toInfo() })
        coordinator.reconcile()
    }

    /**
     * Sends the device's event log for shared alarms as high-level statuses. Each local event has
     * a UUID, so retries are idempotent on the server. Events for personal alarms are marked done
     * without ever leaving the phone.
     */
    suspend fun uploadEvents() {
        while (true) {
            val batch = alarms.unsyncedEvents(BATCH)
            if (batch.isEmpty()) return
            val shared = store.shared.value.keys
            val settled = mutableListOf<String>()
            val outgoing = mutableListOf<OutgoingEvent>()
            for (e in batch) {
                val alarmId = e.occurrenceId.substringBeforeLast('@')
                val social = if (alarmId in shared) PublicStatusMapper.eventFor(AlarmState.valueOf(e.toState)) else null
                if (social == null) {
                    settled += e.eventId
                } else {
                    val status = (social as? SocialEvent.StatusChanged)?.status
                    outgoing += OutgoingEvent(e.eventId, e.occurrenceId, social.type, status, e.at)
                }
            }
            for (chunk in outgoing.chunked(MAX_PER_REQUEST)) {
                val results = guarded { api.postEvents(chunk) }
                results.filter { it.settled }.mapTo(settled) { it.eventId }
                results.filter { it.result == "rejected" }.forEach { Log.w(TAG, "Event ${it.eventId} rejected: ${it.reason}") }
            }
            alarms.markEventsSynced(settled)
            if (batch.size < BATCH) return
        }
    }

    private suspend fun upsertLocal(remote: RemoteAlarm) {
        val now = clock.now().toEpochMilli()
        val existing = alarms.alarm(remote.id)
        val base = existing ?: AlarmEntity(
            alarmId = remote.id,
            label = remote.label,
            hour = remote.hour,
            minute = remote.minute,
            repeatDaysMask = remote.repeatDaysMask,
            oneTimeDate = remote.oneTimeDate,
            anchorZone = remote.zoneId,
            enabled = true,
            vibrate = true,
            soundUri = null,
            snoozeMinutes = 9,
            maxSnoozes = 3,
            ringDurationMinutes = RingPolicy.DEFAULT_RING_MINUTES,
            createdAt = now,
            updatedAt = now,
        )
        // Schedule fields come from the server; sound, vibration, snooze and ring length stay personal.
        val updated = base.copy(
            label = remote.label,
            hour = remote.hour,
            minute = remote.minute,
            repeatDaysMask = remote.repeatDaysMask,
            oneTimeDate = remote.oneTimeDate,
            anchorZone = remote.zoneId,
            updatedAt = now,
        )
        if (existing == null || existing.copy(updatedAt = 0) != updated.copy(updatedAt = 0)) alarms.saveAlarm(updated)
    }

    /** A 401 means the session was revoked or expired: sign out locally so the UI can say so. */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.status == 401 && e.code != "invalid_google_token") {
            store.clearAccount()
            scheduler.cancel()
        }
        throw e
    }

    private fun AlarmEntity.toInput(zone: String) = AlarmInput(label, hour, minute, repeatDaysMask, oneTimeDate, zone)

    private fun RemoteAlarm.toInfo() = SharedInfo(version, myRole == "OWNER", ownerName, participants.size)

    private companion object {
        const val TAG = "SocialRepository"
        const val BATCH = 200
        const val MAX_PER_REQUEST = 100
    }
}
