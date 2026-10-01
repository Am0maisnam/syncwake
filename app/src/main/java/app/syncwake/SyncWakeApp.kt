package app.syncwake

import android.app.Application
import app.syncwake.notify.Notifications

class SyncWakeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Runs during Direct Boot too: only device-protected storage may be touched here.
        Notifications.createChannels(this)
    }
}
