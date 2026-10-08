package app.syncwake

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.syncwake.ui.editor.AlarmEditorScreen
import app.syncwake.ui.history.HistoryScreen
import app.syncwake.social.WorkManagerSyncScheduler
import app.syncwake.ui.home.HomeScreen
import app.syncwake.ui.social.GroupStatusScreen
import app.syncwake.ui.settings.SettingsScreen
import app.syncwake.ui.theme.SyncWakeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opening the app is a cheap, safe moment to make sure every alarm is registered.
        val graph = Graph.get(this)
        lifecycleScope.launch { graph.coordinator.reconcile() }
        // ...and to catch up with friends' shared alarms.
        if (graph.social.config.isConfigured && graph.social.signedIn) {
            WorkManagerSyncScheduler(this).apply {
                schedulePeriodic()
                requestNow()
            }
        }

        setContent {
            SyncWakeTheme {
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                val requestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotifications()
                    }
                }

                // route: "home" | "new" | "edit:<alarmId>" | "status:<alarmId>" | "history" | "settings"
                var route by rememberSaveable { mutableStateOf("home") }
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when {
                        route == "home" -> HomeScreen(
                            onCreateAlarm = { route = "new" },
                            onEditAlarm = { route = "edit:$it" },
                            onOpenHistory = { route = "history" },
                            onOpenSettings = { route = "settings" },
                            onRequestNotificationPermission = requestNotifications,
                            onOpenStatus = { route = "status:$it" },
                        )
                        route == "new" -> AlarmEditorScreen(alarmId = null, onDone = { route = "home" })
                        route.startsWith("edit:") -> AlarmEditorScreen(
                            alarmId = route.removePrefix("edit:"),
                            onDone = { route = "home" },
                            onOpenStatus = { route = "status:$it" },
                        )
                        route.startsWith("status:") -> GroupStatusScreen(alarmId = route.removePrefix("status:"), onBack = { route = "home" })
                        route == "history" -> HistoryScreen(onBack = { route = "home" })
                        route == "settings" -> SettingsScreen(onBack = { route = "home" })
                    }
                }
            }
        }
    }
}
