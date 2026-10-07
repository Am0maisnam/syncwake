# SyncWake architecture and roadmap

## 1. Starting point

The repository was empty, so this is a fresh build in Kotlin and Jetpack Compose, following the
product specification. Priorities, in order: reliability, wakefulness, accountability, social,
competition, sleep insights.

## 2. Architecture

```
core/domain (pure Kotlin, unit-tested on any JVM)
  alarm/      AlarmSchedule, NextTriggerCalculator, AlarmReconciler, Snooze/Overdue/Ring/WakeProof policies
  state/      AlarmState, AlarmEvent, AlarmStateMachine (explicit transition table)
  challenge/  code generator, ChallengeSession (20 s timer), EscalationPolicy, BrightnessCurve
  readiness/  Smart Alarm Preparation rules

app (Android)
  data/       Room: alarms, occurrences, occurrence_events (future sync outbox), challenge_attempts
  alarm/      AlarmScheduler (AlarmManager), AlarmCoordinator (applies reconciler), WakeProofManager, receivers
  ring/       AlarmRingingService (foreground), AlarmAudioPlayer (fallback chain), AlarmVibrator
  ui/         Compose screens: home, editor, ring/challenge, wake proof, history, settings
```

### Local-first alarm path (no network anywhere)

```
setAlarmClock(trigger, PendingIntent -> AlarmReceiver)
  -> AlarmReceiver.onReceive: startForegroundService(AlarmRingingService)   (no I/O first)
  -> service: startForeground() immediately, then load the occurrence from Room
  -> transition SCHEDULED -> RINGING, play audio (custom -> bundled -> system), vibrate
  -> custom/voice sound still playing after 1 minute with no Dismiss -> switch to the built-in tone
     (AlarmSoundEscalation; never mid-challenge; a failed challenge after 1 minute resumes on it)
  -> full-screen intent -> AlarmActivity (over the lock screen)
  -> Dismiss -> DISMISS_CHALLENGE (audio lowered, not muted; 20 s timer starts now)
       success -> COMPLETED -> (wake proof check 1 minute later) ... -> CONFIRMED_AWAKE
       failure/timeout/abandon -> CHALLENGE_FAILED -> RINGING (full volume)
```

### Occurrences and identity

Each ring is an *occurrence* with the deterministic id `"<alarmId>@<localDate>"`. Every
participant's device will compute the same id for the same morning, which is the basis for
idempotent completion events, Wake-Up Race ordering and multi-device de-duplication in later phases.

### Reconciliation

`AlarmReconciler` is a pure function from (alarms, pending occurrences, now, zone) to actions
(`Schedule`, `ScheduleSnooze`, `RingNow`, `MarkMissed`, `Cancel`, `DisableOneTimeAlarm`).
`AlarmCoordinator` applies them. It runs on app start, alarm edits, `LOCKED_BOOT_COMPLETED`,
`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, the exact-alarm
permission broadcast, and whenever an alarm fires. It is idempotent.

- Overdue by 15 minutes or less (phone was off, process died mid-ring): ring now.
- Overdue by more than 15 minutes: record MISSED.
- `RingNow` re-fires through AlarmManager rather than starting the service directly, so the ring
  always starts from an exact-alarm broadcast (which is what permits a background foreground-service start).
- An occurrence that already progressed past SCHEDULED is never reset (e.g. flying west after
  completing an alarm).

### Time

Authoritative timestamps are UTC epoch milliseconds. Durations (challenge timer, ring timeout)
use `elapsedRealtime`, so changing the clock can't extend a challenge. Alarms are *floating*
(they follow the device zone) by default; synced group alarms will use a *fixed* zone
(`TimeAnchor.Fixed`). DST: times that don't exist ring at the shifted time; repeated times ring once.

## 3. Platform rules this relies on

Checked against developer.android.com on 2026-10-01:

| Area | What we do | Source |
|---|---|---|
| Exact alarms | `USE_EXACT_ALARM` (API 33+, auto-granted, not revocable) plus `SCHEDULE_EXACT_ALARM` with `maxSdkVersion=32`. Check `canScheduleExactAlarms()`; fall back to `setAndAllowWhileIdle` and tell the user. | "Schedule alarms" guide |
| Doze | `setAlarmClock()`: "the system never adjusts their delivery time ... leaves low-power modes if necessary". | same |
| Reboot | All alarms are cleared on shutdown; we re-register from Room on (locked) boot. | same |
| Full-screen intent | Limited to calling/alarm apps for target 34+; check `canUseFullScreenIntent()`, link to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`; otherwise a heads-up notification. | Android 14 behavior changes |
| Foreground service type | `systemExempted` (permitted for apps holding exact-alarm permission), falling back to `mediaPlayback`. | FGS types page |
| Android 16 | No alarm, full-screen intent or boot-receiver changes listed; job quota changes don't affect us (no jobs in the ring path). | Android 16 behavior changes |

**Not verified (blocked from this environment):** the current Google Play policy text for
`USE_EXACT_ALARM` eligibility (support.google.com was unreachable). Alarm clocks are the intended
use case, but confirm during Play review. If Play rejects it, remove `USE_EXACT_ALARM`. The app
already handles a revoked or denied `SCHEDULE_EXACT_ALARM`.

### Known limitations
- Brightness is set on the alarm window only (`WindowManager.LayoutParams.screenBrightness`); the
  starting level is approximated from `Settings.System.SCREEN_BRIGHTNESS`, which uses a different
  scale on some devices.
- Some OEM battery managers kill apps in ways AOSP doesn't. `setAlarmClock` is the most robust
  mechanism available; readiness suggests disabling battery optimization, but it doesn't require it.
- In Direct Boot (before the first unlock) custom sounds stored as content URIs are usually not
  readable; the bundled fallback plays instead.

## 4. Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 Reliability | local scheduling, reboot recovery, offline, alarm UI, state machine, fallback audio | **done (this PR)** |
| 2 Wake challenge | 7-char generator, 20 s timer, brightness, haptics, attempts, escalation | **done (this PR)** |
| 3 Social alarm | accounts, backend, invitations, participants, per-user completion, live status, event sync | next |
| 4 Smart Wake Verification | short post-alarm sensor/interaction monitoring, confidence model, privacy settings | planned |
| 5 Social engagement | streaks, nudges, reactions, partners, clubs, group progress | planned |
| 6 Premium competition | entitlement system, Wake-Up Race, medals, history | planned |
| 7 Premium sleep | Sleep Sync, privacy model, ranking, medals, statistics | planned |
| 8 Premium audio | voice recording, upload, assignment, caching, fallback | planned |
| 9 Recaps and polish | weekly recap, advanced stats, accessibility pass, performance | planned |

### Proposed backend (Phase 3, not yet built)
Cloudflare Workers + D1 (normalized schema per spec §69, migrations), Durable Objects for
per-group live status over WebSockets, FCM for push, Google Play Billing with server-side
verification (RTDN) for server-authoritative entitlements. The client syncs the
`occurrence_events` table as an idempotent outbox (unique `eventId`). The server assigns
Wake-Up Race order from receipt time plus plausibility checks, never from client clocks alone.

## 5. Test coverage vs. spec §80

Implemented now (`core/domain` unit tests and `app` Robolectric tests):

| # | Scenario | Where |
|---|---|---|
| 1-3 | App closed / locked / offline | by design: AlarmManager + foreground service, no network in the ring path. Device test still needed. |
| 4 | Reboot before alarm | `AlarmCoordinatorTest.rebootLosesRegistrationAndReconcileRestoresIt`, `AlarmReconcilerTest` |
| 5 | Challenge success | `ChallengeTest.correctCodeCaseInsensitiveSucceeds` |
| 6 | 20 s timeout | `ChallengeTest.timeoutAtTwentySecondsFails`, `timerStartsAtDismissNotAtRing` |
| 7 | Wrong challenge | `ChallengeTest.wrongCharactersAreNotAppendedAndFailAfterLimit` |
| 8 | Repeated failures | `AlarmStateMachineTest.repeatedFailuresThenSuccess`, `ChallengeTest.escalationIsCappedAndCodeStaysSeven` |
| 9 | Brightness progression | `ChallengeTest.brightnessIncreasesMonotonicallyToMax` |
| 15 (partial) | No activity -> Wake-Up Proof | wake proof is currently always used (`AlarmCoordinatorTest.ignoredWakeProofReAlertsThroughAlarmManager`) |
| 17-18 | Wake-Up Proof success / ignored | `AlarmStateMachineTest`, `AlarmCoordinatorTest` |
| — | Custom sound -> built-in tone after 1 minute | `AlarmSoundEscalationTest` (policy); device test needed for the audio switch |
| 32 | Fallback alarm | `AlarmAudioPlayer` chain (device test needed); readiness `FALLBACK_AUDIO_MISSING` |
| 33 | Time-zone change | `AlarmReconcilerTest.timeZoneChangeMovesFloatingAlarm`, `flyingEastCanMakeAnAlarmOverdue` |
| 39 | Server unavailable during alarm | by design: no server dependency |

Still needed: instrumented/device tests for 1-4, 10 and 32 (haptics and audio need real hardware),
plus everything from Phase 3 onward.
