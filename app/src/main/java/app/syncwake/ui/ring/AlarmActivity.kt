package app.syncwake.ui.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.syncwake.ui.theme.SyncWakeTheme

/**
 * Full-screen alarm UI, shown over the lock screen via the ringing notification's full-screen
 * intent. It never stops the alarm itself: it sends commands to the ringing service, which owns
 * all state transitions.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SyncWakeTheme { RingScreen(onBrightness = ::setWindowBrightness, onFinished = ::finish) } }
    }

    override fun onDestroy() {
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        super.onDestroy()
    }

    /** Affects only this window; the system brightness setting is never changed. */
    private fun setWindowBrightness(level: Float) {
        val attrs = window.attributes
        if (attrs.screenBrightness != level) {
            attrs.screenBrightness = level
            window.attributes = attrs
        }
    }

    companion object {
        private const val EXTRA_OCCURRENCE_ID = "occurrenceId"

        fun intent(context: Context, occurrenceId: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
