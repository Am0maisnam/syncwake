package app.syncwake.ui.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.syncwake.Graph
import app.syncwake.ui.theme.SyncWakeColors
import app.syncwake.ui.theme.SyncWakeTheme
import kotlinx.coroutines.launch

/** "Still awake? 👀" — a single deliberate tap confirms the user is awake. */
class WakeProofActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
        if (occurrenceId == null) {
            finish()
            return
        }
        val graph = Graph.get(this)
        setContent {
            SyncWakeTheme {
                val scope = rememberCoroutineScope()
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Still awake? 👀", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Confirm you're up so your morning counts as awake.",
                        color = SyncWakeColors.Muted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                    )
                    Button(
                        onClick = { scope.launch { graph.wakeProof.confirm(occurrenceId); finish() } },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SyncWakeColors.Accent, contentColor = Color.Black),
                    ) { Text("I'M AWAKE", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_OCCURRENCE_ID = "occurrenceId"

        fun intent(context: Context, occurrenceId: String): Intent =
            Intent(context, WakeProofActivity::class.java)
                .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
