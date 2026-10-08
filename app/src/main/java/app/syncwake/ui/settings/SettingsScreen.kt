package app.syncwake.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.syncwake.Graph
import app.syncwake.settings.TimeFormat
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Graph.get(context).settings }
    val s by settings.state.collectAsState()
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text("SETTINGS", style = MaterialTheme.typography.headlineMedium)
        }

        Text("CLOCK", style = MonoLabel, color = SyncWakeColors.Muted)
        Column(Modifier.selectableGroup()) {
            for ((format, label) in listOf(
                TimeFormat.SYSTEM to "Use phone setting",
                TimeFormat.H12 to "12-hour (7:00 AM)",
                TimeFormat.H24 to "24-hour (07:00)",
            )) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = s.timeFormat == format, role = Role.RadioButton) {
                            settings.update { it.copy(timeFormat = format) }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.timeFormat == format, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }

        Text("WAKE-UP PROOF", style = MonoLabel, color = SyncWakeColors.Muted, modifier = Modifier.padding(top = 8.dp))
        Text(
            "1 minute after you complete the challenge, SyncWake asks \"Still awake? 👀\". " +
                "Answer with one tap. Smart Wake Verification, which can skip this check when your phone " +
                "shows you're clearly up, is coming in a later update.",
            color = SyncWakeColors.Muted,
        )
        Toggle("Ask \"Still awake?\" after alarms", s.wakeProofEnabled) { v -> settings.update { it.copy(wakeProofEnabled = v) } }
        Toggle("Ring again if I don't answer", s.wakeProofReAlert) { v -> settings.update { it.copy(wakeProofReAlert = v) } }

        Text("ACCESSIBILITY", style = MonoLabel, color = SyncWakeColors.Muted, modifier = Modifier.padding(top = 8.dp))
        Toggle("Extended challenge time (60 seconds)", s.extendedChallengeTime) { v ->
            settings.update { it.copy(extendedChallengeTime = v) }
        }
        Toggle("No extra steps after repeated failures", s.disableCognitiveEscalation) { v ->
            settings.update { it.copy(disableCognitiveEscalation = v) }
        }
        Toggle("Don't change screen brightness during the challenge", s.reduceBrightnessChanges) { v ->
            settings.update { it.copy(reduceBrightnessChanges = v) }
        }
    }
}

@Composable
private fun Toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
