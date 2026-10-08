package app.syncwake.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.syncwake.Graph
import app.syncwake.data.OccurrenceEntity
import app.syncwake.data.alarmState
import app.syncwake.domain.state.AlarmState
import app.syncwake.ui.Formatting
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors
import java.time.Duration
import java.time.Instant

/**
 * Basic local history. The free plan shows the last 7 days; longer ranges arrive with the
 * entitlement system (Premium).
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { Graph.get(context) }
    val since = remember { Instant.now().minus(Duration.ofDays(FREE_HISTORY_DAYS)).toEpochMilli() }
    val history by graph.repository.observeHistory(since).collectAsState(initial = emptyList())
    BackHandler(onBack = onBack)

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                Column {
                    Text("HISTORY", style = MaterialTheme.typography.headlineMedium)
                    Text("LAST $FREE_HISTORY_DAYS DAYS", style = MonoLabel, color = SyncWakeColors.Muted)
                }
            }
        }
        if (history.isEmpty()) item { Text("Nothing here yet.", color = SyncWakeColors.Muted) }
        items(history, key = { it.occurrenceId }) { HistoryRow(it) }
    }
}

@Composable
private fun HistoryRow(o: OccurrenceEntity) {
    val (emoji, text) = describe(o.alarmState)
    Row(
        Modifier
            .fillMaxWidth()
            .background(SyncWakeColors.Surface, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.alarmLabel.ifBlank { "Alarm" }, style = MaterialTheme.typography.titleMedium)
            Text(Formatting.dateTime(LocalContext.current, o.triggerAt), color = SyncWakeColors.Muted)
            o.completedAt?.let { done ->
                val seconds = Duration.ofMillis(done - (o.lastFiredAt ?: o.triggerAt)).seconds.coerceAtLeast(0)
                Text("Completed after ${seconds / 60}m ${seconds % 60}s · ${o.challengeFailures} failed attempt(s)", color = SyncWakeColors.Muted)
            }
        }
        Text("$emoji $text", style = MonoLabel)
    }
}

private fun describe(state: AlarmState): Pair<String, String> = when (state) {
    AlarmState.CONFIRMED_AWAKE -> "✅" to "Awake"
    AlarmState.COMPLETED -> "☑️" to "Completed"
    AlarmState.PASSIVE_WAKE_MONITORING, AlarmState.PROBABLY_AWAKE -> "⏳" to "Verifying"
    AlarmState.WAKE_PROOF_REQUIRED -> "👀" to "Proof needed"
    AlarmState.WAKE_STATUS_UNKNOWN -> "❔" to "Unverified"
    AlarmState.MISSED -> "❌" to "Missed"
    AlarmState.CANCELLED -> "—" to "Cancelled"
    AlarmState.SNOOZED -> "💤" to "Snoozed"
    AlarmState.RINGING, AlarmState.DISMISS_CHALLENGE, AlarmState.CHALLENGE_FAILED -> "🔔" to "Ringing"
    AlarmState.SCHEDULED -> "🕒" to "Scheduled"
}

private const val FREE_HISTORY_DAYS = 7L
