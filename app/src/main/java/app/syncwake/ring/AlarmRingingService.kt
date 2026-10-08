package app.syncwake.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.syncwake.Graph
import app.syncwake.R
import app.syncwake.data.ChallengeAttemptEntity
import app.syncwake.data.alarmState
import app.syncwake.data.snoozePolicy
import app.syncwake.domain.alarm.AlarmSoundEscalation
import app.syncwake.domain.alarm.RingPolicy
import app.syncwake.domain.alarm.SnoozePolicy
import app.syncwake.domain.challenge.ChallengeConfig
import app.syncwake.domain.state.AlarmEvent
import app.syncwake.domain.state.AlarmState
import app.syncwake.domain.state.ChallengeFailureReason
import app.syncwake.notify.Notifications
import app.syncwake.ui.Formatting
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plays ringing alarms. Started by [app.syncwake.alarm.AlarmReceiver] when an exact alarm fires
 * (which exempts it from background foreground-service start restrictions), goes foreground
 * immediately, and is the single writer of ringing-related state transitions.
 *
 * Alarms that fire while another is ringing are queued; each must be completed with its own
 * challenge. Nothing here depends on the network.
 */
class AlarmRingingService : Service() {

    private class Active(
        val id: String,
        val label: String,
        val soundUri: Uri?,
        val vibrate: Boolean,
        val snooze: SnoozePolicy,
        val ringDurationMillis: Long,
        var state: AlarmState,
        var failures: Int,
        var snoozeCount: Int,
    ) {
        /** elapsedRealtime when the current ring started; drives the custom-sound time limit. */
        var ringStartedElapsed: Long = 0
        /** The custom sound has been replaced by the built-in tone for this ring. */
        var soundEscalated: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val graph by lazy { Graph.get(this) }

    private lateinit var audio: AlarmAudioPlayer
    private lateinit var vibrator: AlarmVibrator
    private var wakeLock: PowerManager.WakeLock? = null

    private val queue = ArrayDeque<Active>()
    private var ringDeadlineElapsed = 0L
    private var isForeground = false

    private val ringTimeout = Runnable { scope.launch { mutex.withLock { onRingTimeout() } } }
    private val soundEscalation = AlarmSoundEscalation()
    private val customSoundLimitReached = Runnable { scope.launch { mutex.withLock { queue.firstOrNull()?.let { applySoundEscalation(it) } } } }

    private val challengeWatchdog = Runnable {
        scope.launch { mutex.withLock { queue.firstOrNull()?.let { onChallengeFailed(it, ChallengeFailureReason.ABANDONED) } } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = AlarmAudioPlayer(this)
        vibrator = AlarmVibrator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Restarted after the process was killed mid-ring. Starting a foreground service from
            // here is not allowed on modern Android, so hand back to AlarmManager: the reconciler
            // sees the interrupted occurrence and re-fires it through an exact alarm right away.
            scope.launch {
                graph.coordinator.reconcile()
                if (queue.isEmpty()) stopSelf()
            }
            return START_NOT_STICKY
        }
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
        when (intent.action) {
            ACTION_FIRE -> {
                if (occurrenceId == null) return stopIfIdle()
                // Must be called promptly and before any suspension.
                goForeground(occurrenceId, getString(R.string.alarm_default_label), canSnooze = false)
                scope.launch { mutex.withLock { onFire(occurrenceId) } }
            }
            ACTION_SNOOZE -> scope.launch { mutex.withLock { onSnooze() } }
            ACTION_BEGIN_CHALLENGE -> if (occurrenceId != null) {
                val limit = intent.getLongExtra(EXTRA_TIME_LIMIT, ChallengeConfig.DEFAULT_TIME_LIMIT_MILLIS)
                scope.launch { mutex.withLock { onBeginChallenge(occurrenceId, limit) } }
            }
            ACTION_CHALLENGE_RESULT -> if (occurrenceId != null) {
                val outcome = intent.getStringExtra(EXTRA_OUTCOME) ?: OUTCOME_ABANDONED
                val attempt = ChallengeAttemptEntity(
                    attemptId = UUID.randomUUID().toString(),
                    occurrenceId = occurrenceId,
                    startedAt = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis()),
                    durationMillis = intent.getLongExtra(EXTRA_DURATION, 0),
                    outcome = outcome,
                    correctChars = intent.getIntExtra(EXTRA_CORRECT, 0),
                    wrongChars = intent.getIntExtra(EXTRA_WRONG, 0),
                    escalationLevel = intent.getIntExtra(EXTRA_ESCALATION, 0),
                )
                scope.launch { mutex.withLock { onChallengeResult(occurrenceId, attempt) } }
            }
            else -> return stopIfIdle()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        vibrator.stop()
        releaseWakeLock()
        RingingRegistry.publish(null, emptySet())
        scope.cancel()
        super.onDestroy()
    }

    // region Commands

    private suspend fun onFire(occurrenceId: String) {
        val now = System.currentTimeMillis()
        val existing = queue.firstOrNull { it.id == occurrenceId }
        if (existing != null) {
            // Duplicate delivery: keep ringing as is (restarting would reset the custom-sound limit).
            publish()
            return
        }

        val active = try {
            loadAndFire(occurrenceId, now)
        } catch (e: Exception) {
            // Never stay silent because local data could not be read: ring with safe defaults.
            Log.e(TAG, "Could not load $occurrenceId; ringing with defaults", e)
            Active(occurrenceId, getString(R.string.alarm_default_label), null, true, SnoozePolicy(), DEFAULT_RING_MILLIS, AlarmState.RINGING, 0, 0)
        }
        if (active == null) {
            Log.i(TAG, "Ignoring stale ring for $occurrenceId")
            if (queue.isEmpty()) stopEverything()
            return
        }
        queue.addLast(active)
        Notifications.cancelStatus(this, occurrenceId)
        if (queue.first() === active) startRinging(active)
        publish()
        // Plan the next occurrence of recurring alarms now, while this one is still ringing.
        scope.launch { graph.coordinator.reconcile() }
    }

    /** @return null if the occurrence should not ring (cancelled, completed, alarm deleted...). */
    private suspend fun loadAndFire(occurrenceId: String, now: Long): Active? {
        val repo = graph.repository
        val occurrence = repo.occurrence(occurrenceId) ?: return null
        val state = occurrence.alarmState
        if (state != AlarmState.SCHEDULED && state != AlarmState.SNOOZED && state != AlarmState.RINGING) return null
        val alarm = repo.alarm(occurrence.alarmId)
        if (alarm == null || (state == AlarmState.SCHEDULED && !alarm.enabled)) return null
        val fired = repo.transition(occurrenceId, AlarmEvent.Fire, now) { it.copy(lastFiredAt = now, snoozeUntil = null) }
            ?: return null
        return Active(
            id = occurrenceId,
            label = alarm.label,
            soundUri = alarm.soundUri?.let(Uri::parse),
            vibrate = alarm.vibrate,
            snooze = alarm.snoozePolicy,
            ringDurationMillis = alarm.ringDurationMinutes.coerceIn(1, 60) * 60_000L,
            state = fired.alarmState,
            failures = fired.challengeFailures,
            snoozeCount = fired.snoozeCount,
        )
    }

    private suspend fun onSnooze() {
        val head = queue.firstOrNull() ?: run {
            stopIfIdle()
            return
        }
        if (head.state != AlarmState.RINGING || !head.snooze.canSnooze(head.snoozeCount)) return
        val now = Instant.now()
        val until = head.snooze.snoozeUntil(now).toEpochMilli()
        graph.repository.transition(head.id, AlarmEvent.Snooze, now.toEpochMilli()) {
            it.copy(snoozeCount = it.snoozeCount + 1, snoozeUntil = until)
        } ?: return
        graph.scheduler.scheduleRing(head.id, until)
        val untilText = Formatting.time(this, until)
        Notifications.showSnoozed(this, head.id, head.label, untilText)
        advance()
    }

    private suspend fun onBeginChallenge(occurrenceId: String, timeLimitMillis: Long) {
        val head = queue.firstOrNull()?.takeIf { it.id == occurrenceId } ?: return
        if (head.state != AlarmState.RINGING) return
        graph.repository.transition(head.id, AlarmEvent.BeginChallenge, System.currentTimeMillis()) ?: return
        head.state = AlarmState.DISMISS_CHALLENGE
        // Lower (never mute) the alarm and pause vibration so haptic feedback is perceptible.
        audio.setVolume(CHALLENGE_VOLUME)
        vibrator.stop()
        handler.removeCallbacks(ringTimeout)
        // Starting the challenge counts as waking up: never switch sounds mid-challenge.
        handler.removeCallbacks(customSoundLimitReached)
        handler.removeCallbacks(challengeWatchdog)
        handler.postDelayed(challengeWatchdog, timeLimitMillis + WATCHDOG_GRACE_MILLIS)
        publish()
    }

    private suspend fun onChallengeResult(occurrenceId: String, attempt: ChallengeAttemptEntity) {
        val head = queue.firstOrNull()?.takeIf { it.id == occurrenceId } ?: return
        if (head.state != AlarmState.DISMISS_CHALLENGE) return
        handler.removeCallbacks(challengeWatchdog)
        runCatching { graph.repository.recordAttempt(attempt) }.onFailure { Log.e(TAG, "recordAttempt", it) }
        if (attempt.outcome == OUTCOME_SUCCEEDED) {
            val now = System.currentTimeMillis()
            val checkAt = graph.wakeProof.checkTimeAfterCompletion(now)
            graph.repository.transition(head.id, AlarmEvent.ChallengeSucceeded, now) {
                it.copy(completedAt = now, wakeProofCheckAt = checkAt)
            }
            if (checkAt != null) graph.wakeProof.scheduleCheck(head.id, checkAt)
            advance()
        } else {
            val reason = when (attempt.outcome) {
                OUTCOME_WRONG_CODE -> ChallengeFailureReason.WRONG_CODE
                OUTCOME_TIMED_OUT -> ChallengeFailureReason.TIMED_OUT
                else -> ChallengeFailureReason.ABANDONED
            }
            onChallengeFailed(head, reason)
        }
    }

    private suspend fun onChallengeFailed(head: Active, reason: ChallengeFailureReason) {
        if (head.state != AlarmState.DISMISS_CHALLENGE) return
        handler.removeCallbacks(challengeWatchdog)
        val now = System.currentTimeMillis()
        val repo = graph.repository
        repo.transition(head.id, AlarmEvent.ChallengeFailed(reason), now) { it.copy(challengeFailures = it.challengeFailures + 1) }
        val resumed = repo.transition(head.id, AlarmEvent.ResumeRinging, now)
        head.failures++
        head.state = resumed?.alarmState ?: AlarmState.RINGING
        // Past the custom-sound limit, ringing resumes on the built-in tone.
        applySoundEscalation(head)
        audio.setVolume(1f)
        if (head.vibrate) vibrator.start()
        // The ring timeout was paused during the challenge; give at least one more minute.
        val remaining = (ringDeadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(MIN_RESUME_RING_MILLIS)
        ringDeadlineElapsed = SystemClock.elapsedRealtime() + remaining
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, remaining)
        publish()
    }

    private suspend fun onRingTimeout() {
        val head = queue.firstOrNull() ?: return
        if (head.state != AlarmState.RINGING) return // paused during a challenge
        graph.repository.transition(head.id, AlarmEvent.MarkMissed, System.currentTimeMillis())
        Notifications.showMissed(this, head.id, head.label)
        advance()
    }

    // endregion

    private fun startRinging(active: Active) {
        audio.setVolume(1f)
        active.ringStartedElapsed = SystemClock.elapsedRealtime()
        active.soundEscalated = false
        audio.start(active.soundUri)
        applySoundEscalation(active)
        if (active.vibrate) vibrator.start() else vibrator.stop()
        acquireWakeLock(active.ringDurationMillis + WAKE_LOCK_MARGIN_MILLIS)
        ringDeadlineElapsed = SystemClock.elapsedRealtime() + active.ringDurationMillis
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, active.ringDurationMillis)
        goForeground(active.id, active.label, canSnooze = active.snooze.canSnooze(active.snoozeCount))
    }

    /**
     * Custom/voice sounds play for at most [AlarmSoundEscalation.customSoundLimit] of ringing; after
     * that the built-in tone takes over. Called when ringing starts or resumes and when the limit
     * elapses. Does nothing during a challenge.
     */
    private fun applySoundEscalation(active: Active) {
        handler.removeCallbacks(customSoundLimitReached)
        if (queue.firstOrNull() !== active || active.state != AlarmState.RINGING) return
        val ringingFor = Duration.ofMillis(SystemClock.elapsedRealtime() - active.ringStartedElapsed)
        val hasCustom = active.soundUri != null
        when (soundEscalation.soundFor(hasCustom, ringingFor, active.soundEscalated)) {
            AlarmSoundEscalation.Sound.CUSTOM -> {
                val wait = soundEscalation.timeUntilSwitch(hasCustom, ringingFor, active.soundEscalated) ?: return
                handler.postDelayed(customSoundLimitReached, wait.toMillis())
            }
            AlarmSoundEscalation.Sound.DEFAULT -> if (hasCustom && !active.soundEscalated) {
                active.soundEscalated = true
                // If the custom sound already failed, the built-in tone is playing; leave it alone.
                if (audio.playingCustom) {
                    Log.i(TAG, "Custom sound limit reached for ${active.id}; switching to the built-in tone")
                    audio.playDefault()
                }
            }
        }
    }

    /** Finish the head occurrence and ring the next queued one, or stop. */
    private fun advance() {
        queue.removeFirstOrNull()
        handler.removeCallbacks(customSoundLimitReached)
        handler.removeCallbacks(ringTimeout)
        handler.removeCallbacks(challengeWatchdog)
        audio.stop()
        vibrator.stop()
        val next = queue.firstOrNull()
        if (next != null) {
            startRinging(next)
            publish()
        } else {
            stopEverything()
        }
    }

    private fun publish() {
        val head = queue.firstOrNull()
        RingingRegistry.publish(
            head?.let {
                RingingUi(
                    occurrenceId = it.id,
                    label = it.label,
                    state = it.state,
                    challengeFailures = it.failures,
                    canSnooze = it.state == AlarmState.RINGING && it.snooze.canSnooze(it.snoozeCount),
                    queued = queue.size - 1,
                )
            },
            queue.map { it.id }.toSet(),
        )
    }

    private fun goForeground(occurrenceId: String, label: String, canSnooze: Boolean) {
        val notification = Notifications.ringing(this, occurrenceId, label, canSnooze)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    // Permitted for apps holding USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM.
                    startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                } catch (e: SecurityException) {
                    Log.w(TAG, "systemExempted not permitted; using mediaPlayback", e)
                    startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                }
            } else {
                startForeground(Notifications.ID_RINGING, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            // Keep ringing even if the system refuses foreground status; audio still plays.
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        vibrator.stop()
        releaseWakeLock()
        RingingRegistry.publish(null, emptySet())
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun stopIfIdle(): Int {
        if (queue.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun acquireWakeLock(timeoutMillis: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "syncwake:ringing")
            .apply { acquire(timeoutMillis) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AlarmRingingService"

        const val ACTION_FIRE = "app.syncwake.action.FIRE"
        const val ACTION_SNOOZE = "app.syncwake.action.SNOOZE"
        const val ACTION_BEGIN_CHALLENGE = "app.syncwake.action.BEGIN_CHALLENGE"
        const val ACTION_CHALLENGE_RESULT = "app.syncwake.action.CHALLENGE_RESULT"

        const val EXTRA_OCCURRENCE_ID = "occurrenceId"
        const val EXTRA_TIME_LIMIT = "timeLimit"
        const val EXTRA_OUTCOME = "outcome"
        const val EXTRA_STARTED_AT = "startedAt"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_CORRECT = "correct"
        const val EXTRA_WRONG = "wrong"
        const val EXTRA_ESCALATION = "escalation"

        const val OUTCOME_SUCCEEDED = "SUCCEEDED"
        const val OUTCOME_WRONG_CODE = "WRONG_CODE"
        const val OUTCOME_TIMED_OUT = "TIMED_OUT"
        const val OUTCOME_ABANDONED = "ABANDONED"

        private const val CHALLENGE_VOLUME = 0.3f
        private const val DEFAULT_RING_MILLIS = RingPolicy.DEFAULT_RING_MINUTES * 60_000L
        private const val MIN_RESUME_RING_MILLIS = 60_000L
        private const val WATCHDOG_GRACE_MILLIS = 5_000L
        private const val WAKE_LOCK_MARGIN_MILLIS = 5 * 60_000L

        /** Called from the exact-alarm broadcast; must not do any blocking work first. */
        fun fire(context: Context, occurrenceId: String) {
            val intent = Intent(context, AlarmRingingService::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun snoozeIntent(context: Context): Intent =
            Intent(context, AlarmRingingService::class.java).setAction(ACTION_SNOOZE)

        fun send(context: Context, intent: Intent) {
            intent.setClass(context, AlarmRingingService::class.java)
            context.startService(intent)
        }
    }
}
