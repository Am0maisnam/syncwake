import { getAlarm, type AlarmRow } from "./alarms";
import { isoDayOfWeek, parseLocalDate, zonedTimeToUtc } from "./time";
import { isUuid, parseOccurrenceId } from "./validation";

/**
 * The only participant states ever shared with friends. Raw device activity (movement, screen,
 * unlocks) has no representation here and cannot be uploaded.
 */
export const PUBLIC_STATUSES = [
  "SCHEDULED",
  "RINGING",
  "SNOOZED",
  "COMPLETING_CHALLENGE",
  "WAKE_VERIFICATION",
  "WAKE_PROOF_REQUIRED",
  "AWAKE_CONFIRMED",
  "MISSED",
] as const;
export type PublicStatus = (typeof PUBLIC_STATUSES)[number];

export const EVENT_TYPES = ["STATUS_CHANGED", "ALARM_COMPLETED", "WAKE_PROOF_COMPLETED"] as const;
type EventType = (typeof EVENT_TYPES)[number];

/** Events whose device clock is further ahead than this are rejected. */
export const MAX_CLOCK_AHEAD_MS = 5 * 60 * 1000;
/** Allowance for a device clock that is slightly behind the alarm time. */
export const MAX_CLOCK_BEHIND_MS = 2 * 60 * 1000;
/** Offline events older than this (relative to the alarm) are no longer accepted. */
export const STALE_AFTER_MS = 7 * 24 * 60 * 60 * 1000;

export type EventResult =
  | { eventId: string; result: "accepted" | "duplicate" }
  | { eventId: string; result: "rejected"; reason: string };

export interface IncomingEvent {
  eventId: string;
  occurrenceId: string;
  type: EventType;
  status: PublicStatus;
  clientAt: number;
}

export interface AcceptedChange {
  alarmId: string;
  occurrenceId: string;
  userId: string;
  status: PublicStatus;
}

/** Parses one event, keeping only known fields; anything else in the payload is discarded. */
export function parseEvent(raw: unknown): IncomingEvent | { eventId: string; reason: string } {
  const r = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
  const eventId = typeof r.eventId === "string" ? r.eventId : "";
  if (!isUuid(eventId)) return { eventId, reason: "invalid_event_id" };
  if (typeof r.occurrenceId !== "string") return { eventId, reason: "invalid_occurrence" };
  if (!EVENT_TYPES.includes(r.type as EventType)) return { eventId, reason: "invalid_type" };
  if (typeof r.clientAt !== "number" || !Number.isFinite(r.clientAt)) return { eventId, reason: "invalid_client_at" };
  const type = r.type as EventType;
  let status: PublicStatus;
  if (type === "ALARM_COMPLETED") status = "WAKE_VERIFICATION";
  else if (type === "WAKE_PROOF_COMPLETED") status = "AWAKE_CONFIRMED";
  else if (PUBLIC_STATUSES.includes(r.status as PublicStatus)) status = r.status as PublicStatus;
  else return { eventId, reason: "invalid_status" };
  return { eventId, occurrenceId: r.occurrenceId, type, status, clientAt: r.clientAt };
}

function scheduleCovers(alarm: AlarmRow, date: string): boolean {
  const d = parseLocalDate(date);
  if (!d) return false;
  if (alarm.repeat_days_mask === 0) return alarm.one_time_date === date;
  return (alarm.repeat_days_mask & (1 << (isoDayOfWeek(d) - 1))) !== 0;
}

/**
 * Validates and applies one event. Idempotent: replaying an accepted event id returns
 * "duplicate" and changes nothing. Client timestamps are used only for plausibility checks and
 * to order a single user's own status updates — never for competitive ordering.
 */
export async function ingestEvent(
  db: D1Database,
  userId: string,
  deviceId: string,
  e: IncomingEvent,
  now: number,
): Promise<{ result: EventResult; change?: AcceptedChange }> {
  const reject = (reason: string) => ({ result: { eventId: e.eventId, result: "rejected" as const, reason } });

  const seen = await db.prepare("SELECT user_id FROM sync_events WHERE event_id = ?").bind(e.eventId).first<{ user_id: string }>();
  if (seen) {
    return seen.user_id === userId ? { result: { eventId: e.eventId, result: "duplicate" } } : reject("event_id_conflict");
  }

  const occ = parseOccurrenceId(e.occurrenceId);
  if (!occ) return reject("invalid_occurrence");
  const participant = await db
    .prepare("SELECT 1 AS ok FROM alarm_participants WHERE alarm_id = ? AND user_id = ? AND status = 'ACTIVE'")
    .bind(occ.alarmId, userId)
    .first();
  if (!participant) return reject("not_participant");
  const alarm = await getAlarm(db, occ.alarmId);
  if (!scheduleCovers(alarm, occ.date)) return reject("not_scheduled");

  const scheduledAt = zonedTimeToUtc(parseLocalDate(occ.date)!, alarm.hour, alarm.minute, alarm.zone_id);
  if (e.clientAt > now + MAX_CLOCK_AHEAD_MS) return reject("clock_ahead");
  if (e.clientAt < scheduledAt - MAX_CLOCK_BEHIND_MS && e.status !== "SCHEDULED") return reject("before_alarm");
  if (now - scheduledAt > STALE_AFTER_MS) return reject("stale");

  const statements: D1PreparedStatement[] = [
    db
      .prepare(
        `INSERT INTO sync_events (event_id, user_id, device_id, occurrence_id, type, status, client_at, received_at, result)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'accepted')`,
      )
      .bind(e.eventId, userId, deviceId, e.occurrenceId, e.type, e.status, e.clientAt, now),
    // Newer (by this user's own device clock) wins; out-of-order offline uploads never regress it.
    db
      .prepare(
        `INSERT INTO participant_status (occurrence_id, alarm_id, user_id, status, client_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (occurrence_id, user_id) DO UPDATE SET status = excluded.status, client_at = excluded.client_at,
                updated_at = excluded.updated_at
          WHERE excluded.client_at >= participant_status.client_at`,
      )
      .bind(e.occurrenceId, alarm.id, userId, e.status, e.clientAt, now),
  ];
  if (e.type === "ALARM_COMPLETED") {
    // First completion per user wins, whichever of their devices sent it.
    statements.push(
      db
        .prepare(
          `INSERT OR IGNORE INTO completions (occurrence_id, alarm_id, user_id, device_id, event_id, client_completed_at, server_received_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)`,
        )
        .bind(e.occurrenceId, alarm.id, userId, deviceId, e.eventId, e.clientAt, now),
    );
  }
  await db.batch(statements);
  return {
    result: { eventId: e.eventId, result: "accepted" },
    change: { alarmId: alarm.id, occurrenceId: e.occurrenceId, userId, status: e.status },
  };
}

export async function occurrenceStatus(db: D1Database, alarmId: string, occurrenceId: string) {
  const { results } = await db
    .prepare(
      `SELECT p.user_id, u.display_name, COALESCE(s.status, 'SCHEDULED') AS status, s.updated_at
         FROM alarm_participants p
         JOIN users u ON u.id = p.user_id
         LEFT JOIN participant_status s ON s.occurrence_id = ? AND s.user_id = p.user_id
        WHERE p.alarm_id = ? AND p.status = 'ACTIVE'
        ORDER BY p.joined_at`,
    )
    .bind(occurrenceId, alarmId)
    .all<{ user_id: string; display_name: string; status: PublicStatus; updated_at: number | null }>();
  const participants = results.map((r) => ({
    userId: r.user_id,
    displayName: r.display_name,
    status: r.status,
    updatedAt: r.updated_at,
  }));
  return {
    occurrenceId,
    alarmId,
    awake: participants.filter((p) => p.status === "AWAKE_CONFIRMED").length,
    total: participants.length,
    participants,
  };
}
