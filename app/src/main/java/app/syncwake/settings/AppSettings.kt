package app.syncwake.settings

import android.content.Context
import android.content.SharedPreferences
import app.syncwake.domain.alarm.WakeProofPolicy
import app.syncwake.domain.challenge.ChallengeAccessibility
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSnapshot(
    val wakeProofEnabled: Boolean = true,
    val wakeProofDelayMinutes: Int = 5,
    val wakeProofReAlert: Boolean = true,
    val extendedChallengeTime: Boolean = false,
    val disableCognitiveEscalation: Boolean = false,
    val reduceBrightnessChanges: Boolean = false,
) {
    val wakeProofPolicy: WakeProofPolicy
        get() = WakeProofPolicy(
            enabled = wakeProofEnabled,
            checkAfter = Duration.ofMinutes(wakeProofDelayMinutes.coerceIn(1, 30).toLong()),
            reAlertOnIgnore = wakeProofReAlert,
        )

    val challengeAccessibility: ChallengeAccessibility
        get() = ChallengeAccessibility(
            timeMultiplier = if (extendedChallengeTime) 2.0 else 1.0,
            disableCognitiveEscalation = disableCognitiveEscalation,
        )
}

/**
 * Device-local preferences, stored in device-protected storage because the ringing path reads
 * them before the first unlock after a reboot.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()
    val current: SettingsSnapshot get() = _state.value

    fun update(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        val next = transform(_state.value)
        prefs.edit()
            .putBoolean(K_WP_ENABLED, next.wakeProofEnabled)
            .putInt(K_WP_DELAY, next.wakeProofDelayMinutes)
            .putBoolean(K_WP_REALERT, next.wakeProofReAlert)
            .putBoolean(K_EXT_TIME, next.extendedChallengeTime)
            .putBoolean(K_NO_COGNITIVE, next.disableCognitiveEscalation)
            .putBoolean(K_REDUCE_BRIGHTNESS, next.reduceBrightnessChanges)
            .apply()
        _state.value = next
    }

    private fun read(): SettingsSnapshot {
        val d = SettingsSnapshot()
        return SettingsSnapshot(
            wakeProofEnabled = prefs.getBoolean(K_WP_ENABLED, d.wakeProofEnabled),
            wakeProofDelayMinutes = prefs.getInt(K_WP_DELAY, d.wakeProofDelayMinutes),
            wakeProofReAlert = prefs.getBoolean(K_WP_REALERT, d.wakeProofReAlert),
            extendedChallengeTime = prefs.getBoolean(K_EXT_TIME, d.extendedChallengeTime),
            disableCognitiveEscalation = prefs.getBoolean(K_NO_COGNITIVE, d.disableCognitiveEscalation),
            reduceBrightnessChanges = prefs.getBoolean(K_REDUCE_BRIGHTNESS, d.reduceBrightnessChanges),
        )
    }

    private companion object {
        const val K_WP_ENABLED = "wake_proof_enabled"
        const val K_WP_DELAY = "wake_proof_delay_minutes"
        const val K_WP_REALERT = "wake_proof_realert"
        const val K_EXT_TIME = "a11y_extended_time"
        const val K_NO_COGNITIVE = "a11y_no_cognitive"
        const val K_REDUCE_BRIGHTNESS = "a11y_reduce_brightness"
    }
}
