package app.syncwake.social

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.syncwake.Graph
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How social sync is triggered; an interface so tests can run sync without WorkManager. */
interface SyncScheduler {
    fun requestNow()
    fun schedulePeriodic()
    fun cancel()
}

/**
 * WorkManager-backed sync: once as soon as the network allows after any change, plus every
 * 15 minutes as a safety net. WorkManager keeps its data in credential-protected storage, so
 * nothing is scheduled before the first unlock after a reboot (alarms don't need it).
 */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val context = context.applicationContext

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun workManager(): WorkManager? {
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!unlocked) return null
        return try {
            WorkManager.getInstance(context)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "WorkManager unavailable", e)
            null
        }
    }

    override fun requestNow() {
        val request = OneTimeWorkRequestBuilder<SocialSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager()?.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SocialSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        workManager()?.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager()?.apply {
            cancelUniqueWork(NOW)
            cancelUniqueWork(PERIODIC)
        }
    }

    private companion object {
        const val TAG = "SocialSync"
        const val NOW = "social-sync-now"
        const val PERIODIC = "social-sync-periodic"
    }
}

class SocialSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val social = Graph.get(applicationContext).social
        if (!social.config.isConfigured || !social.signedIn) return Result.success()
        return try {
            social.syncNow()
            Result.success()
        } catch (e: ApiException) {
            // Client errors won't fix themselves by retrying (except rate limiting).
            if (e.status in 400..499 && e.status != 429) Result.success() else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        }
    }
}
