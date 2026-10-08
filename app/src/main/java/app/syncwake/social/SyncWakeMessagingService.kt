package app.syncwake.social

import app.syncwake.Graph
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Data-only pushes from the server ("alarm changed", "friend's status changed"). They never
 * contain private details; they just prompt a sync.
 */
class SyncWakeMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // Firebase is initialised from build config (no google-services.json), so make sure it's
        // ready before the base class handles anything.
        SocialConfig.fromBuild.ensureFirebase(this)
        super.onCreate()
    }

    override fun onNewToken(token: String) {
        scope.launch { Graph.get(applicationContext).social.onNewPushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val social = Graph.get(applicationContext).social
        message.data["alarmId"]?.let(social::notifyLiveChange)
        WorkManagerSyncScheduler(applicationContext).requestNow()
    }
}
