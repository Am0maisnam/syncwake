# SyncWake

Android social alarm app (Kotlin, Jetpack Compose, Room). Read `docs/ARCHITECTURE.md` first.

## Layout
- `core/domain/` — pure Kotlin (no Android). State machine, scheduling/reconciliation, challenge,
  readiness rules. An *included build*: test with `./gradlew -p core/domain test`.
- `app/` — Android: Room DB (device-protected storage), AlarmManager scheduling, ringing
  foreground service, receivers, Compose UI.

## Rules
- Alarm reliability beats everything else. Never make ringing depend on the network.
- Every occurrence state change goes through `AlarmStateMachine` (via `AlarmRepository.transition`).
- Every scheduling change goes through `AlarmCoordinator.reconcile()`; don't call AlarmManager elsewhere.
- Put decision logic in `core/domain` with unit tests; keep `app/` as thin platform glue.
- Room schema changes need a Migration; never enable destructive migration.
- Timestamps are epoch-millis UTC; format only in the UI.
