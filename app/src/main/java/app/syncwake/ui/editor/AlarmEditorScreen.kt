package app.syncwake.ui.editor

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.syncwake.Graph
import app.syncwake.data.AlarmEntity
import app.syncwake.data.daysToMask
import app.syncwake.data.maskToDays
import app.syncwake.domain.alarm.ALARM_LABEL_MAX_LENGTH
import app.syncwake.domain.alarm.NextTriggerCalculator
import app.syncwake.domain.alarm.RingPolicy
import app.syncwake.ui.Formatting
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun AlarmEditorScreen(alarmId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { Graph.get(context) }
    val scope = rememberCoroutineScope()
    val is24h = remember { Formatting.is24Hour(context) }

    var loaded by remember { mutableStateOf(alarmId == null) }
    var existing by remember { mutableStateOf<AlarmEntity?>(null) }
    var hour by remember { mutableIntStateOf(7) }
    var minute by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf<DayOfWeek>()) }
    var vibrate by remember { mutableStateOf(true) }
    var soundUri by remember { mutableStateOf<String?>(null) }
    var snoozeMinutes by remember { mutableIntStateOf(9) }
    var maxSnoozes by remember { mutableIntStateOf(3) }
    var ringMinutes by remember { mutableIntStateOf(RingPolicy.DEFAULT_RING_MINUTES) }

    LaunchedEffect(alarmId) {
        if (alarmId != null) {
            graph.repository.alarm(alarmId)?.let { a ->
                existing = a
                hour = a.hour; minute = a.minute; label = a.label
                days = maskToDays(a.repeatDaysMask); vibrate = a.vibrate; soundUri = a.soundUri
                snoozeMinutes = a.snoozeMinutes; maxSnoozes = a.maxSnoozes; ringMinutes = a.ringDurationMinutes
            }
            loaded = true
        }
    }

    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            soundUri = uri?.toString()
        }
    }

    BackHandler(onBack = onDone)
    if (!loaded) return

    fun save() {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(hour, minute)
        val alarm = AlarmEntity(
            localId = existing?.localId ?: 0,
            alarmId = existing?.alarmId ?: UUID.randomUUID().toString(),
            label = label.trim().take(ALARM_LABEL_MAX_LENGTH),
            hour = hour,
            minute = minute,
            repeatDaysMask = daysToMask(days),
            oneTimeDate = if (days.isEmpty()) NextTriggerCalculator.defaultOneTimeDate(time, now, zone).toString() else null,
            anchorZone = null,
            enabled = true,
            vibrate = vibrate,
            soundUri = soundUri,
            snoozeMinutes = snoozeMinutes,
            maxSnoozes = maxSnoozes,
            ringDurationMinutes = ringMinutes,
            createdAt = existing?.createdAt ?: now.toEpochMilli(),
            updatedAt = now.toEpochMilli(),
        )
        scope.launch {
            graph.coordinator.saveAlarm(alarm)
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text(if (existing == null) "NEW ALARM" else "EDIT ALARM", style = MaterialTheme.typography.headlineMedium)
        }

        TimeSelector(
            hour = hour, minute = minute, is24h = is24h,
            onHour = { hour = it }, onMinute = { minute = it },
        )

        Section("ALARM DESIGNATION") {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(ALARM_LABEL_MAX_LENGTH) },
                placeholder = { Text("Sunrise Workout & Gym") },
                singleLine = true,
                supportingText = { Text("${label.length}/$ALARM_LABEL_MAX_LENGTH", style = MonoLabel) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("RECURRENCE CADENCE") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (day in DayOfWeek.entries) {
                    val selected = day in days
                    val name = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(if (selected) SyncWakeColors.Accent else SyncWakeColors.SurfaceHigh, RoundedCornerShape(10.dp))
                            .toggleable(value = selected, role = Role.Checkbox, onValueChange = {
                                days = if (it) days + day else days - day
                            })
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            style = MonoLabel,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else SyncWakeColors.Muted,
                        )
                    }
                }
            }
            Text(
                if (days.isEmpty()) "Rings once" else "Repeats weekly",
                color = SyncWakeColors.Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Section("SOUND") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (soundUri == null) "Built-in alarm" else "Custom system sound",
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    pickSound.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, soundUri?.let(Uri::parse)),
                    )
                }) { Text("CHOOSE") }
                if (soundUri != null) TextButton(onClick = { soundUri = null }) { Text("RESET") }
            }
            Text(
                "A chosen sound plays for the first minute. If you haven't started dismissing by then, " +
                    "or it can't play, the built-in alarm takes over.",
                color = SyncWakeColors.Muted,
            )
            ToggleRow("Vibrate", vibrate) { vibrate = it }
        }

        Section("SNOOZE & DURATION") {
            Stepper("Snooze length", "$snoozeMinutes min", snoozeMinutes, 1..30) { snoozeMinutes = it }
            Stepper("Snoozes allowed", if (maxSnoozes == 0) "Off" else "$maxSnoozes", maxSnoozes, 0..10) { maxSnoozes = it }
            Stepper("Ring for", "$ringMinutes min", ringMinutes, 1..60) { ringMinutes = it }
        }

        Button(
            onClick = ::save,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SyncWakeColors.Accent, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) { Text("SAVE ALARM", style = MaterialTheme.typography.titleMedium) }

        existing?.let { alarm ->
            OutlinedButton(
                onClick = { scope.launch { graph.coordinator.deleteAlarm(alarm.alarmId); onDone() } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                border = BorderStroke(1.dp, SyncWakeColors.Error),
            ) { Text("DELETE ALARM", color = SyncWakeColors.Error) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TimeSelector(hour: Int, minute: Int, is24h: Boolean, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) {
    val displayHour = if (is24h) hour else ((hour + 11) % 12) + 1
    val pm = hour >= 12
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, SyncWakeColors.Outline, RoundedCornerShape(16.dp))
            .background(SyncWakeColors.Surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CHRONO-LOCK", style = MonoLabel, color = SyncWakeColors.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(
                value = "%02d".format(displayHour),
                description = "Hour",
                onUp = { onHour((hour + 1) % 24) },
                onDown = { onHour((hour + 23) % 24) },
            )
            Text(":", style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(horizontal = 8.dp))
            Spinner(
                value = "%02d".format(minute),
                description = "Minute",
                onUp = { onMinute((minute + 1) % 60) },
                onDown = { onMinute((minute + 59) % 60) },
            )
            if (!is24h) {
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AmPm("AM", selected = !pm) { if (pm) onHour(hour - 12) }
                    AmPm("PM", selected = pm) { if (!pm) onHour(hour + 12) }
                }
            }
        }
    }
}

@Composable
private fun Spinner(value: String, description: String, onUp: () -> Unit, onDown: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onUp, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Increase $description")
        }
        Text(
            value,
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.semantics { contentDescription = "$description $value" },
        )
        IconButton(onClick = onDown, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Decrease $description")
        }
    }
}

@Composable
private fun AmPm(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(56.dp)
            .height(44.dp)
            .background(if (selected) SyncWakeColors.Accent else SyncWakeColors.SurfaceHigh, RoundedCornerShape(6.dp))
            .toggleable(value = selected, role = Role.RadioButton, onValueChange = { onClick() })
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MonoLabel, color = if (selected) MaterialTheme.colorScheme.onPrimary else SyncWakeColors.Muted)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MonoLabel, color = SyncWakeColors.Muted, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Stepper(text: String, valueText: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text(valueText, style = MonoLabel, textAlign = TextAlign.Center, modifier = Modifier.width(72.dp))
        TextButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}
