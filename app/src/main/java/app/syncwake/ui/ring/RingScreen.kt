package app.syncwake.ui.ring

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.syncwake.Graph
import app.syncwake.domain.challenge.BrightnessCurve
import app.syncwake.domain.challenge.ChallengeCodeGenerator
import app.syncwake.domain.challenge.ChallengeSession.Outcome
import app.syncwake.domain.challenge.ChallengeSession.Reason
import app.syncwake.domain.challenge.EscalationPolicy
import app.syncwake.domain.state.AlarmState
import app.syncwake.ring.AlarmRingingService
import app.syncwake.ring.RingingRegistry
import app.syncwake.ring.RingingUi
import app.syncwake.ui.theme.MonoLabel
import app.syncwake.ui.theme.SyncWakeColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun RingScreen(onBrightness: (Float) -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { Graph.get(context) }
    val ringing by RingingRegistry.current.collectAsState()
    var controller by remember { mutableStateOf<ChallengeController?>(null) }
    var completedMessage by remember { mutableStateOf(false) }

    fun report(occurrenceId: String, c: ChallengeController, outcome: Outcome) {
        val name = when (outcome) {
            Outcome.Succeeded -> AlarmRingingService.OUTCOME_SUCCEEDED
            is Outcome.Failed -> when (outcome.reason) {
                Reason.WRONG_CODE -> AlarmRingingService.OUTCOME_WRONG_CODE
                Reason.TIMED_OUT -> AlarmRingingService.OUTCOME_TIMED_OUT
                Reason.ABANDONED -> AlarmRingingService.OUTCOME_ABANDONED
            }
            else -> return
        }
        AlarmRingingService.send(
            context,
            Intent(AlarmRingingService.ACTION_CHALLENGE_RESULT)
                .putExtra(AlarmRingingService.EXTRA_OCCURRENCE_ID, occurrenceId)
                .putExtra(AlarmRingingService.EXTRA_OUTCOME, name)
                .putExtra(AlarmRingingService.EXTRA_STARTED_AT, c.startedAtWallMillis)
                .putExtra(AlarmRingingService.EXTRA_DURATION, System.currentTimeMillis() - c.startedAtWallMillis)
                .putExtra(AlarmRingingService.EXTRA_CORRECT, c.correctCount)
                .putExtra(AlarmRingingService.EXTRA_WRONG, c.wrongCount)
                .putExtra(AlarmRingingService.EXTRA_ESCALATION, c.escalationLevel),
        )
        if (outcome == Outcome.Succeeded) {
            haptic(view, Haptic.COMPLETE)
            completedMessage = true
        } else {
            haptic(view, Haptic.ERROR)
        }
        controller = null
    }

    val current = ringing
    // Abandon an in-progress challenge if the screen goes away (power button, home, task switch).
    val latestController by rememberUpdatedState(controller)
    val latestRinging by rememberUpdatedState(current)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val c = latestController
                val r = latestRinging
                if (c != null && r != null) report(r.occurrenceId, c, c.abandon())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Progressive brightness, applied to this window only.
    val settings = graph.settings.current
    val startBrightness = remember(controller) { systemBrightnessFraction(context) }
    val curve = BrightnessCurve(startLevel = startBrightness, maxLevel = 1f, reduceBrightnessChanges = settings.reduceBrightnessChanges)
    val c = controller
    val target = when {
        c == null || settings.reduceBrightnessChanges -> -1f
        else -> curve.levelFor(c.correctCount, c.code.length)
    }
    val animated by animateFloatAsState(if (target < 0) startBrightness else target, tween(300), label = "brightness")
    LaunchedEffect(animated, target) {
        onBrightness(if (target < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else animated)
    }

    if (current == null) {
        LaunchedEffect(Unit) {
            delay(if (completedMessage) 1_500 else 500)
            if (RingingRegistry.current.value == null) onFinished()
        }
        CenteredMessage(if (completedMessage) "Alarm complete ✓" else "Alarm stopped")
        return
    }

    BackHandler {
        // Back never dismisses. During a challenge it abandons the attempt and the alarm resumes.
        controller?.let { report(current.occurrenceId, it, it.abandon()) }
    }

    if (c == null && completedMessage && current.state != AlarmState.RINGING) {
        // The service is finishing this occurrence; don't flash the Dismiss button again.
        CenteredMessage("Alarm complete ✓")
    } else if (c == null) {
        RingingView(
            ui = current,
            onDismiss = {
                completedMessage = false
                val created = ChallengeController(current.challengeFailures, settings.challengeAccessibility)
                controller = created
                AlarmRingingService.send(
                    context,
                    Intent(AlarmRingingService.ACTION_BEGIN_CHALLENGE)
                        .putExtra(AlarmRingingService.EXTRA_OCCURRENCE_ID, current.occurrenceId)
                        .putExtra(AlarmRingingService.EXTRA_TIME_LIMIT, created.timeLimitMillis),
                )
            },
            onSnooze = { AlarmRingingService.send(context, Intent(AlarmRingingService.ACTION_SNOOZE)) },
            timeLimitSeconds = EscalationPolicy.configFor(current.challengeFailures, settings.challengeAccessibility).timeLimitMillis / 1000,
        )
    } else {
        LaunchedEffect(c) {
            while (!c.session.isFinished) {
                c.tick()?.let { report(current.occurrenceId, c, it) }
                delay(100)
            }
        }
        ChallengeView(
            controller = c,
            onKey = { ch ->
                when (val outcome = c.enter(ch)) {
                    is Outcome.Correct, Outcome.CodeComplete -> haptic(view, Haptic.CORRECT)
                    is Outcome.Wrong -> haptic(view, Haptic.ERROR)
                    Outcome.Succeeded, is Outcome.Failed -> report(current.occurrenceId, c, outcome)
                    Outcome.Ignored -> Unit
                }
            },
            onDigit = { haptic(view, Haptic.INPUT); c.typeDigit(it) },
            onBackspace = { haptic(view, Haptic.INPUT); c.backspace() },
            onSubmit = {
                val outcome = c.submitArithmetic()
                if (outcome != Outcome.Ignored) report(current.occurrenceId, c, outcome)
            },
        )
    }
}

@Composable
private fun RingingView(ui: RingingUi, onDismiss: () -> Unit, onSnooze: () -> Unit, timeLimitSeconds: Long) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Text("ALARM", style = MonoLabel, color = SyncWakeColors.Muted)
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.displayLarge)
            Text(ui.label.ifBlank { "Wake up" }, style = MaterialTheme.typography.headlineMedium)
            if (ui.challengeFailures > 0) {
                Text(
                    "Attempts so far: ${ui.challengeFailures}",
                    color = SyncWakeColors.Warning,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (ui.queued > 0) {
                Text("${ui.queued} more alarm(s) waiting", color = SyncWakeColors.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "To stop the alarm, type a 7-character code within $timeLimitSeconds seconds.",
                color = SyncWakeColors.Muted,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SyncWakeColors.Accent, contentColor = Color.Black),
            ) { Text("DISMISS", style = MaterialTheme.typography.titleMedium) }
            if (ui.canSnooze) {
                OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("SNOOZE")
                }
            }
        }
    }
}

@Composable
private fun ChallengeView(
    controller: ChallengeController,
    onKey: (Char) -> Unit,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
) {
    val seconds = (controller.remainingMillis + 999) / 1000
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(controller.wrongPulse) {
        if (controller.wrongPulse > 0) {
            flash = true
            delay(250)
            flash = false
        }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PROOF OF WAKEFULNESS", style = MonoLabel, color = SyncWakeColors.Muted, modifier = Modifier.weight(1f))
            Text(
                "${seconds}s",
                style = MonoLabel.copy(fontSize = 18.sp),
                color = if (seconds <= 5) SyncWakeColors.Error else SyncWakeColors.OnBackground,
                modifier = Modifier.semantics { contentDescription = "$seconds seconds left" },
            )
        }
        LinearProgressIndicator(
            progress = { controller.remainingMillis.toFloat() / controller.timeLimitMillis },
            modifier = Modifier.fillMaxWidth(),
        )
        CodeDisplay(controller.code, controller.correctCount, flash)
        Text(
            "${controller.correctCount} of ${controller.code.length} entered",
            color = SyncWakeColors.Muted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (controller.inArithmeticStep) {
            val q = controller.session.arithmetic!!
            Text("Last step: ${q.prompt} = ?", style = MaterialTheme.typography.headlineMedium)
            Text(controller.arithmeticInput.ifEmpty { " " }, style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp))
            DigitPad(onDigit, onBackspace, onSubmit)
        } else {
            CodeKeypad(onKey)
        }
    }
}

@Composable
private fun CodeDisplay(code: String, correct: Int, flash: Boolean) {
    val spoken = code.toList().joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, if (flash) SyncWakeColors.Error else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Code: $spoken" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        code.forEachIndexed { i, ch ->
            val done = i < correct
            val next = i == correct
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(0.8f)
                    .background(if (done) SyncWakeColors.Success.copy(alpha = 0.25f) else SyncWakeColors.SurfaceHigh, RoundedCornerShape(8.dp))
                    .border(if (next) 2.dp else 0.dp, if (next) SyncWakeColors.Accent else Color.Transparent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ch.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    color = if (done) SyncWakeColors.Success else SyncWakeColors.OnBackground,
                )
            }
        }
    }
}

@Composable
private fun CodeKeypad(onKey: (Char) -> Unit) {
    val keys = ChallengeCodeGenerator.ALPHABET.toList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch -> Key(ch.toString(), Modifier.weight(1f)) { onKey(ch) } }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DigitPad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch -> Key(ch.toString(), Modifier.weight(1f)) { onDigit(ch) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("⌫", Modifier.weight(1f), description = "Delete") { onBackspace() }
            Key("0", Modifier.weight(1f)) { onDigit('0') }
            Key("✓", Modifier.weight(1f), description = "Submit answer") { onSubmit() }
        }
    }
}

@Composable
private fun Key(text: String, modifier: Modifier, description: String = text, onClick: () -> Unit) {
    Box(
        modifier
            .height(56.dp)
            .background(SyncWakeColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineMedium)
    }
}

private enum class Haptic { INPUT, CORRECT, ERROR, COMPLETE }

/** View haptics respect the user's system haptic setting and degrade on basic vibrators. */
private fun haptic(view: View, kind: Haptic) {
    val constant = when (kind) {
        Haptic.INPUT -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.CORRECT -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.ERROR -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        Haptic.COMPLETE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
    }
    view.performHapticFeedback(constant)
}

/** Current system brightness as a 0..1 fraction (approximate; many devices use non-linear curves). */
private fun systemBrightnessFraction(context: android.content.Context): Float = try {
    (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f).coerceIn(0.05f, 1f)
} catch (_: Settings.SettingNotFoundException) {
    0.3f
}
