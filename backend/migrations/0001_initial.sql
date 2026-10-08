-- SyncWake backend schema v1 (Phase 3: accounts, devices, shared alarms, invites, occurrence
-- status and completions). Timestamps are epoch milliseconds (UTC).
--
-- Privacy: there are deliberately no columns for raw sensor data, screen/unlock activity or
-- precise "last active" times. Participant status is a closed set of high-level values.

CREATE TABLE users (
  id            TEXT PRIMARY KEY,
  google_sub    TEXT NOT NULL UNIQUE,
  email         TEXT,
  display_name  TEXT NOT NULL,
  photo_url     TEXT,
  created_at    INTEGER NOT NULL
);

-- Server-authoritative plan. Billing (Phase 6) is the only writer of PREMIUM rows.
CREATE TABLE subscriptions (
  user_id     TEXT PRIMARY KEY REFERENCES users(id),
  plan        TEXT NOT NULL CHECK (plan IN ('FREE', 'PREMIUM')),
  expires_at  INTEGER,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE devices (
  id            TEXT PRIMARY KEY,           -- client-generated UUID, stable per install
  user_id       TEXT NOT NULL REFERENCES users(id),
  platform      TEXT NOT NULL,
  app_version   TEXT,
  fcm_token     TEXT,
  last_seen_at  INTEGER NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE INDEX devices_user ON devices(user_id);

CREATE TABLE sessions (
  token_hash  TEXT PRIMARY KEY,             -- SHA-256 of the bearer token; the token itself is never stored
  user_id     TEXT NOT NULL REFERENCES users(id),
  device_id   TEXT NOT NULL REFERENCES devices(id),
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

CREATE TABLE alarms (
  id                TEXT PRIMARY KEY,
  owner_id          TEXT NOT NULL REFERENCES users(id),
  label             TEXT NOT NULL,
  hour              INTEGER NOT NULL CHECK (hour BETWEEN 0 AND 23),
  minute            INTEGER NOT NULL CHECK (minute BETWEEN 0 AND 59),
  repeat_days_mask  INTEGER NOT NULL CHECK (repeat_days_mask BETWEEN 0 AND 127), -- bit0 = Monday
  one_time_date     TEXT,                   -- YYYY-MM-DD when repeat_days_mask = 0
  zone_id           TEXT NOT NULL,          -- IANA zone: shared alarms ring at the same instant
  status            TEXT NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
  version           INTEGER NOT NULL,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);
CREATE INDEX alarms_owner ON alarms(owner_id);

CREATE TABLE alarm_participants (
  alarm_id   TEXT NOT NULL REFERENCES alarms(id),
  user_id    TEXT NOT NULL REFERENCES users(id),
  role       TEXT NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
  status     TEXT NOT NULL CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
  joined_at  INTEGER NOT NULL,
  PRIMARY KEY (alarm_id, user_id)
);
CREATE INDEX participants_user ON alarm_participants(user_id, status);

CREATE TABLE invites (
  code        TEXT PRIMARY KEY,
  alarm_id    TEXT NOT NULL REFERENCES alarms(id),
  created_by  TEXT NOT NULL REFERENCES users(id),
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  revoked     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX invites_alarm ON invites(alarm_id);

-- Latest high-level status of each participant for each occurrence ("<alarmId>@<YYYY-MM-DD>").
CREATE TABLE participant_status (
  occurrence_id  TEXT NOT NULL,
  alarm_id       TEXT NOT NULL REFERENCES alarms(id),
  user_id        TEXT NOT NULL REFERENCES users(id),
  status         TEXT NOT NULL,
  client_at      INTEGER NOT NULL,          -- device time of the event that set it (ordering only)
  updated_at     INTEGER NOT NULL,          -- server time
  PRIMARY KEY (occurrence_id, user_id)
);
CREATE INDEX status_alarm ON participant_status(alarm_id);

-- One valid challenge completion per user per occurrence, whichever device sent it first.
-- seq is assigned by the server on arrival and gives a deterministic order for ties.
CREATE TABLE completions (
  seq                 INTEGER PRIMARY KEY AUTOINCREMENT,
  occurrence_id       TEXT NOT NULL,
  alarm_id            TEXT NOT NULL REFERENCES alarms(id),
  user_id             TEXT NOT NULL REFERENCES users(id),
  device_id           TEXT NOT NULL REFERENCES devices(id),
  event_id            TEXT NOT NULL UNIQUE,
  client_completed_at INTEGER NOT NULL,
  server_received_at  INTEGER NOT NULL,
  UNIQUE (occurrence_id, user_id)
);

-- Idempotency/replay log for every event a device sends.
CREATE TABLE sync_events (
  event_id       TEXT PRIMARY KEY,
  user_id        TEXT NOT NULL REFERENCES users(id),
  device_id      TEXT NOT NULL REFERENCES devices(id),
  occurrence_id  TEXT NOT NULL,
  type           TEXT NOT NULL,
  status         TEXT,
  client_at      INTEGER NOT NULL,
  received_at    INTEGER NOT NULL,
  result         TEXT NOT NULL
);
CREATE INDEX sync_events_occurrence ON sync_events(occurrence_id);
