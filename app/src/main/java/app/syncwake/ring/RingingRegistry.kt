package app.syncwake.ring

import app.syncwake.domain.state.AlarmState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the alarm screen needs to render the occurrence that is currently ringing. */
data class RingingUi(
    val occurrenceId: String,
    val label: String,
    val state: AlarmState,
    val challengeFailures: Int,
    val canSnooze: Boolean,
    /** More alarms are queued behind this one; each needs its own challenge. */
    val queued: Int,
)

/**
 * In-process view of what the ringing service is doing. The alarm screen observes [current]; the
 * reconciler uses [audibleOccurrenceIds] so it never touches an alarm that is actually playing.
 */
object RingingRegistry {
    private val _current = MutableStateFlow<RingingUi?>(null)
    val current: StateFlow<RingingUi?> = _current.asStateFlow()

    @Volatile
    var audibleOccurrenceIds: Set<String> = emptySet()
        internal set

    internal fun publish(ui: RingingUi?, audible: Set<String>) {
        audibleOccurrenceIds = audible
        _current.value = ui
    }
}
