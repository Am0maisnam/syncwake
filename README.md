# SyncWake

A social alarm clock: an alarm first, an accountability tool second, a friendly competition third.

This repository currently contains **Phase 1 (alarm reliability)** and **Phase 2 (wake challenge)**
of the roadmap in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Social features, Smart Wake
Verification, Premium and the backend come in later phases.

## What works today

- One-time and recurring alarms (any weekdays), labels, enable/disable, edit, delete
- Exact alarms via `AlarmManager.setAlarmClock` — ring through Doze, with the app closed or swiped
  away, offline, and after reboot (including before the first unlock, via Direct Boot)
- Re-scheduling on reboot, app update, clock change, time-zone change and exact-alarm permission grant
- Late-ring / missed detection when the phone was off at alarm time
- Snooze with limits, ring timeout -> missed, alarm history (last 7 days)
- Bundled fallback alarm sound; custom sound -> bundled -> system default chain, so a broken
  custom sound never produces a silent alarm
- Dismissal challenge: 7 random unambiguous characters, case-insensitive, 15 seconds starting
  when you press Dismiss, progressive window brightness, haptics, capped escalation (a simple
  sum after 3 failures), accessibility options (30-second time, no extra steps, no brightness changes)
- Wake-Up Proof ("Still awake? 👀") a few minutes after completing the challenge, with optional,
  bounded re-alert if ignored
- Smart Alarm Preparation: "Your next alarm is ready ✓", or a specific explanation and a FIX button

## Build and test

```sh
# Pure-Kotlin domain logic (state machine, scheduling, challenge, readiness). No Android SDK needed.
./gradlew -p core/domain test

# Android app (needs the Android SDK; CI runs this on every push)
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Requirements: JDK 17+, Android SDK with platform 36. Minimum supported Android version: 8.0 (API 26).
