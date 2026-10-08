package app.syncwake.ui.social

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.syncwake.Graph
import app.syncwake.social.Invite
import app.syncwake.social.RemoteAlarm
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors
import kotlinx.coroutines.launch

/** "Share with friends" switch for a new alarm. */
@Composable
fun ShareToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val social = remember { Graph.get(context).social }
    val account by social.account.collectAsState()
    Column {
        Text("FRIENDS", style = MonoLabel, color = SyncWakeColors.Muted, modifier = Modifier.padding(bottom = 8.dp))
        if (!social.config.isConfigured || account == null) {
            Text(
                if (social.config.isConfigured) "Sign in on the home screen to share alarms with friends."
                else "Friends features aren't connected in this build yet.",
                color = SyncWakeColors.Muted,
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Share with friends")
                Text(
                    "Everyone's phone rings at this exact moment (your time zone). Each person dismisses their own alarm.",
                    color = SyncWakeColors.Muted,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Members, invites and removal for an existing shared alarm. */
@Composable
fun SharedAlarmSection(alarmId: String, isOwner: Boolean, onOpenStatus: () -> Unit) {
    val context = LocalContext.current
    val social = remember { Graph.get(context).social }
    val account by social.account.collectAsState()
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<RemoteAlarm?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var invite by remember { mutableStateOf<Invite?>(null) }

    LaunchedEffect(alarmId) {
        try {
            details = social.details(alarmId)
        } catch (e: Exception) {
            error = socialErrorMessage(e)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FRIENDS", style = MonoLabel, color = SyncWakeColors.Muted)
        details?.let { d ->
            Text("${d.participants.size} of ${d.maxGroupSize} people", color = SyncWakeColors.Muted)
            if (d.restricted) {
                Text("This group is over the Free limit, so no one new can join. Nobody has been removed.", color = SyncWakeColors.Warning)
            }
            for (p in d.participants) {
                Row(
                    Modifier.fillMaxWidth().background(SyncWakeColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        p.displayName + (if (p.userId == account?.userId) " (you)" else "") + (if (p.role == "OWNER") " · owner" else ""),
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                    )
                    if (isOwner && p.role != "OWNER") {
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    details = social.removeParticipant(alarmId, p.userId)
                                } catch (e: Exception) {
                                    socialErrorMessage(e)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                }
                            }
                        }) { Icon(Icons.Filled.Close, contentDescription = "Remove ${p.displayName}") }
                    }
                }
            }
            if (!isOwner) Text("Only ${d.ownerName} can change the time and days.", color = SyncWakeColors.Muted)
        }
        error?.let { Text(it, color = SyncWakeColors.Error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenStatus) { Text("WHO'S UP") }
            if (isOwner) {
                OutlinedButton(onClick = {
                    scope.launch {
                        try {
                            invite = social.invite(alarmId)
                        } catch (e: Exception) {
                            socialErrorMessage(e)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        }
                    }
                }) { Text("INVITE A FRIEND") }
            }
        }
    }

    invite?.let { inv ->
        AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text("Invite a friend") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your friend opens SyncWake, taps JOIN WITH CODE and enters:")
                    Text(inv.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 4.sp)
                    Text("The code works for 7 days.", color = SyncWakeColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = "Wake up with me on SyncWake! Open the app, tap JOIN WITH CODE and enter ${inv.code}"
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invite")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("SHARE") }
            },
            dismissButton = { TextButton(onClick = { invite = null }) { Text("DONE") } },
        )
    }
}
