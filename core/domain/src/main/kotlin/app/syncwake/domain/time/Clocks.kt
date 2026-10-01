package app.syncwake.domain.time

import java.time.Instant
import java.time.ZoneId

/**
 * Wall-clock time. Authoritative timestamps are always [Instant]s (UTC); the zone is only
 * used to resolve local alarm times and to format values for display.
 */
interface WallClock {
    fun now(): Instant
    fun zone(): ZoneId
}

/**
 * Monotonic time that is unaffected by the user changing the device clock or time zone.
 * Used for anything measured as a duration (challenge timers, ring timeouts).
 * On Android this is backed by `SystemClock.elapsedRealtime()`.
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

object SystemWallClock : WallClock {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
