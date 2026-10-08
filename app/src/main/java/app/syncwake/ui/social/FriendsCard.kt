package app.syncwake.ui.social

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.syncwake.Graph
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors
import kotlinx.coroutines.launch

/** Home-screen entry point for everything with friends. */
@Composable
fun FriendsCard() {
    val context = LocalContext.current
    val social = remember { Graph.get(context).social }
    val account by social.account.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }

    fun signIn(then: () -> Unit = {}) {
        val activity = context.findActivity() ?: return
        busy = true
        scope.launch {
            try {
                social.signIn(activity)
                then()
            } catch (e: Exception) {
                socialErrorMessage(e)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            } finally {
                busy = false
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SyncWakeColors.Surface),
        border = BorderStroke(1.dp, SyncWakeColors.Outline),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FRIENDS", style = MonoLabel, color = SyncWakeColors.Muted)
            when {
                !social.config.isConfigured -> {
                    Text("Wake up together with friends", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Friends features aren't connected in this build yet. The SyncWake server and Google sign-in " +
                            "need to be set up first (see docs/BACKEND_SETUP.md). Your alarms work normally.",
                        color = SyncWakeColors.Muted,
                    )
                }
                account == null -> {
                    Text("Wake up together with friends", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Share an alarm with up to 2 friends. Everyone's phone rings at the same moment, and you can " +
                            "see who's up.",
                        color = SyncWakeColors.Muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { signIn() },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = SyncWakeColors.Accent, contentColor = Color.Black),
                        ) { Text(if (busy) "SIGNING IN…" else "SIGN IN WITH GOOGLE") }
                        OutlinedButton(onClick = { signIn { showJoin = true } }, enabled = !busy) { Text("JOIN WITH CODE") }
                    }
                }
                else -> {
                    Text("Signed in as ${account!!.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text("Create an alarm and turn on \"Share with friends\", or join a friend's alarm.", color = SyncWakeColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showJoin = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SyncWakeColors.Accent, contentColor = Color.Black),
                        ) { Text("JOIN WITH CODE") }
                        TextButton(onClick = { scope.launch { social.signOut() } }) { Text("SIGN OUT") }
                    }
                }
            }
        }
    }

    if (showJoin) JoinDialog(onDismiss = { showJoin = false })
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val social = remember { Graph.get(context).social }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a friend's alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the 8-character code your friend shared.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isLetterOrDigit).take(8).uppercase(); error = null },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoLabel.fontFamily),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    placeholder = { Text("K7MHR9XA") },
                )
                error?.let { Text(it, color = SyncWakeColors.Error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.length == 8 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val alarm = social.join(code)
                            Toast.makeText(context, "Joined ${alarm.ownerName}'s alarm", Toast.LENGTH_LONG).show()
                            onDismiss()
                        } catch (e: Exception) {
                            error = socialErrorMessage(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "JOINING…" else "JOIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}
