package app.syncwake.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.syncwake.Graph
import app.syncwake.data.AlarmEntity
import app.syncwake.data.maskToDays
import app.syncwake.domain.readiness.ReadinessIssue
import app.syncwake.domain.readiness.ReadinessReport
import app.syncwake.notify.Notifications
import app.syncwake.ui.Formatting
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onCreateAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { Graph.get(context) }
    val scope = rememberCoroutineScope()
    val alarms by graph.repository.observeAlarms().collectAsState(initial = emptyList())
    val next by graph.repository.observeNextUpcoming().collectAsState(initial = null)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var report by remember { mutableStateOf<ReadinessReport?>(null) }

    LaunchedEffect(lifecycle, next) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) report = graph.readiness.check()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateAlarm,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("NEW ALARM") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SYNCWAKE",
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = MonoLabel.letterSpacing),
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.DateRange, contentDescription = "Alarm history") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            }
            item { NextAlarmHeader(next?.triggerAt) }
            report?.let { r -> item { ReadinessCard(r, hasAlarm = next != null, onFix = { fix(context, it, onRequestNotificationPermission) { scope.launch { graph.coordinator.reconcile() } } }) } }
            if (alarms.isEmpty()) {
                item {
                    Text(
                        "No alarms yet. Tap NEW ALARM to create one.",
                        color = SyncWakeColors.Muted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(alarms, key = { it.alarmId }) { alarm ->
                AlarmRow(
                    alarm = alarm,
                    onClick = { onEditAlarm(alarm.alarmId) },
                    onToggle = { enabled -> scope.launch { graph.coordinator.setEnabled(alarm.alarmId, enabled) } },
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun NextAlarmHeader(triggerAt: Long?) {
    Column {
        Text("NEXT ALARM", style = MonoLabel, color = SyncWakeColors.Muted)
        if (triggerAt == null) {
            Text("None scheduled", style = MaterialTheme.typography.headlineMedium)
        } else {
            val now = Instant.now()
            Text(
                "In " + Formatting.until(now, Instant.ofEpochMilli(triggerAt)),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(Formatting.dateTime(LocalContext.current, triggerAt), color = SyncWakeColors.Muted)
        }
    }
}

@Composable
private fun ReadinessCard(report: ReadinessReport, hasAlarm: Boolean, onFix: (ReadinessIssue) -> Unit) {
    if (!hasAlarm && report.issues.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = SyncWakeColors.Surface),
        border = BorderStroke(1.dp, if (report.ready) SyncWakeColors.Outline else SyncWakeColors.Error),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            if (report.ready && hasAlarm) {
                Text("Your next alarm is ready ✓", color = SyncWakeColors.Success, style = MaterialTheme.typography.titleMedium)
            } else if (!report.ready) {
                Text("Your alarm needs attention", color = SyncWakeColors.Error, style = MaterialTheme.typography.titleMedium)
            }
            for (issue in report.issues) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        issue.message,
                        color = if (issue.blocking) SyncWakeColors.OnBackground else SyncWakeColors.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onFix(issue) }) { Text("FIX") }
                }
            }
        }
    }
}

@Composable
private fun AlarmRow(alarm: AlarmEntity, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val time = Formatting.time(context, LocalTime.of(alarm.hour, alarm.minute))
    val days = Formatting.days(maskToDays(alarm.repeatDaysMask))
    Row(
        Modifier
            .fillMaxWidth()
            .background(SyncWakeColors.Surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Alarm $time, $days, ${alarm.label}, ${if (alarm.enabled) "on" else "off"}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(time, style = MaterialTheme.typography.headlineMedium, color = if (alarm.enabled) SyncWakeColors.OnBackground else SyncWakeColors.Muted)
            Text(listOf(alarm.label, days).filter { it.isNotBlank() }.joinToString(" · "), color = SyncWakeColors.Muted)
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
    }
}

private fun fix(context: Context, issue: ReadinessIssue, requestNotifications: () -> Unit, reschedule: () -> Unit) {
    val pkg = Uri.parse("package:${context.packageName}")
    val intent: Intent? = when (issue) {
        ReadinessIssue.NOT_REGISTERED -> null.also { reschedule() }
        ReadinessIssue.EXACT_ALARM_DENIED ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
        ReadinessIssue.NOTIFICATIONS_BLOCKED -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestNotifications()
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU }
        }
        ReadinessIssue.ALARM_CHANNEL_DISABLED -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_RINGING)
        ReadinessIssue.FULL_SCREEN_DENIED ->
            if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
        ReadinessIssue.BATTERY_OPTIMIZED -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ReadinessIssue.ALARM_VOLUME_MUTED, ReadinessIssue.ALARM_VOLUME_LOW -> {
            context.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            null
        }
        ReadinessIssue.FALLBACK_AUDIO_MISSING, ReadinessIssue.CUSTOM_AUDIO_MISSING -> null
    }
    if (intent != null) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

