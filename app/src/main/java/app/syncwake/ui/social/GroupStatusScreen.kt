package app.syncwake.ui.social

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.syncwake.Graph
import app.syncwake.data.AlarmEntity
import app.syncwake.data.schedule
import app.syncwake.domain.alarm.AlarmSchedule
import app.syncwake.domain.alarm.TimeAnchor
import app.syncwake.domain.alarm.occurrenceId
import app.syncwake.domain.social.PublicStatus
import app.syncwake.social.OccurrenceStatus
import app.syncwake.ui.Formatting
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * "Who's up?" for one shared alarm: each person's high-level status for today's (or the most
 * recent) morning, updated live while the screen is open.
 */
@Composable
fun GroupStatusScreen(alarmId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { Graph.get(context) }
    val social = graph.social
    val account by social.account.collectAsState()
    var alarm by remember { mutableStateOf<AlarmEntity?>(null) }
    var status by remember { mutableStateOf<OccurrenceStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)

    LaunchedEffect(alarmId) { alarm = graph.repository.alarm(alarmId) }
    val occurrence = alarm?.let { latestOccurrenceDate(it.schedule, it.anchorZone)?.let { d -> occurrenceId(alarmId, d) } }

    LaunchedEffect(occurrence, refresh) {
        if (occurrence == null) return@LaunchedEffect
        try {
            status = social.occurrenceStatus(occurrence)
            error = null
        } catch (e: Exception) {
            error = socialErrorMessage(e)
        }
    }
    // Live updates while open: WebSocket for this alarm, plus pushes.
    DisposableEffect(alarmId, occurrence) {
        val socket = social.openLive(alarmId) { update -> if (update.occurrenceId == occurrence) status = update }
        onDispose { socket?.close(1000, "screen closed") }
    }
    LaunchedEffect(alarmId) { social.liveChanges.collect { if (it == alarmId) refresh++ } }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(alarm?.label?.ifBlank { null } ?: "Shared alarm", style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() })
                    alarm?.let {
                        Text(
                            "WHO'S UP · " + Formatting.time(context, LocalTime.of(it.hour, it.minute)),
                            style = MonoLabel, color = SyncWakeColors.Muted,
                        )
                    }
                }
                IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
        }
        if (occurrence == null && alarm != null) {
            item { Text("This alarm hasn't rung recently.", color = SyncWakeColors.Muted) }
        }
        error?.let { item { Text(it, color = SyncWakeColors.Error) } }
        status?.let { s ->
            item {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text("${s.awake} / ${s.total} awake", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    lastPersonStanding(s)?.let { Text(it, color = SyncWakeColors.Warning) }
                }
            }
            items(s.participants, key = { it.userId }) { p ->
                Row(
                    Modifier.fillMaxWidth().background(SyncWakeColors.Surface, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.status.emoji, fontSize = 22.sp, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName + if (p.userId == account?.userId) " (you)" else "", style = MaterialTheme.typography.titleMedium)
                        Text(p.status.label, color = if (p.status == PublicStatus.AWAKE_CONFIRMED) SyncWakeColors.Success else SyncWakeColors.Muted)
                    }
                }
            }
        }
    }
}

/** "Waiting for Alex…" once everyone else is confirmed awake. Encouraging, never shaming. */
private fun lastPersonStanding(s: OccurrenceStatus): String? {
    if (s.total < 2 || s.awake != s.total - 1) return null
    val remaining = s.participants.firstOrNull { it.status != PublicStatus.AWAKE_CONFIRMED } ?: return null
    return if (remaining.status == PublicStatus.MISSED) null else "Waiting for ${remaining.displayName}…"
}

/** Today's date in the alarm's zone if it rings today, else the most recent day it rang (≤ 7 days). */
internal fun latestOccurrenceDate(schedule: AlarmSchedule, anchorZone: String?): LocalDate? {
    val zone = anchorZone?.let(ZoneId::of) ?: (schedule.anchor as? TimeAnchor.Fixed)?.zone ?: ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    if (!schedule.isRecurring) return schedule.oneTimeDate?.takeIf { !it.isAfter(today) && !it.isBefore(today.minusDays(7)) }
    return (0L..7L).map { today.minusDays(it) }.firstOrNull { it.dayOfWeek in schedule.repeatDays }
}
