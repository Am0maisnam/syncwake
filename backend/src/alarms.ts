import { userEntitlements } from "./entitlements";
import { HttpError, type AlarmInput } from "./validation";

export interface AlarmRow {
  id: string;
  owner_id: string;
  label: string;
  hour: number;
  minute: number;
  repeat_days_mask: number;
  one_time_date: string | null;
  zone_id: string;
  status: "ACTIVE" | "CANCELLED";
  version: number;
  created_at: number;
  updated_at: number;
}

export interface ParticipantRow {
  user_id: string;
  display_name: string;
  role: "OWNER" | "MEMBER";
  status: "ACTIVE" | "LEFT" | "REMOVED";
}

export async function getAlarm(db: D1Database, alarmId: string): Promise<AlarmRow> {
  const alarm = await db.prepare("SELECT * FROM alarms WHERE id = ?").bind(alarmId).first<AlarmRow>();
  if (!alarm) throw new HttpError(404, "alarm_not_found");
  return alarm;
}

export async function activeParticipants(db: D1Database, alarmId: string): Promise<ParticipantRow[]> {
  const { results } = await db
    .prepare(
      `SELECT p.user_id, u.display_name, p.role, p.status
         FROM alarm_participants p JOIN users u ON u.id = p.user_id
        WHERE p.alarm_id = ? AND p.status = 'ACTIVE'
        ORDER BY p.role DESC, p.joined_at`,
    )
    .bind(alarmId)
    .all<ParticipantRow>();
  return results;
}

/** The caller must be an active participant; returns their role. Hides existence otherwise. */
export async function requireParticipant(db: D1Database, alarmId: string, userId: string): Promise<"OWNER" | "MEMBER"> {
  const row = await db
    .prepare("SELECT role FROM alarm_participants WHERE alarm_id = ? AND user_id = ? AND status = 'ACTIVE'")
    .bind(alarmId, userId)
    .first<{ role: "OWNER" | "MEMBER" }>();
  if (!row) throw new HttpError(404, "alarm_not_found");
  return row.role;
}

export async function requireOwner(db: D1Database, alarmId: string, userId: string): Promise<AlarmRow> {
  const role = await requireParticipant(db, alarmId, userId);
  if (role !== "OWNER") throw new HttpError(403, "owner_only");
  return getAlarm(db, alarmId);
}

export async function alarmView(db: D1Database, alarm: AlarmRow, viewerId: string, now: number) {
  const participants = await activeParticipants(db, alarm.id);
  const owner = await userEntitlements(db, alarm.owner_id, now);
  return {
    id: alarm.id,
    ownerId: alarm.owner_id,
    label: alarm.label,
    hour: alarm.hour,
    minute: alarm.minute,
    repeatDaysMask: alarm.repeat_days_mask,
    oneTimeDate: alarm.one_time_date,
    zoneId: alarm.zone_id,
    status: alarm.status,
    version: alarm.version,
    myRole: participants.find((p) => p.user_id === viewerId)?.role ?? null,
    maxGroupSize: owner.maxGroupSize,
    // After a Premium -> Free downgrade a group can exceed the Free limit. Nobody is removed and
    // alarms keep ringing; the group just can't grow until it is back within the limit.
    restricted: participants.length > owner.maxGroupSize,
    participants: participants.map((p) => ({ userId: p.user_id, displayName: p.display_name, role: p.role })),
  };
}

export async function createAlarm(db: D1Database, ownerId: string, input: AlarmInput, now: number): Promise<AlarmRow> {
  const id = crypto.randomUUID();
  await db.batch([
    db
      .prepare(
        `INSERT INTO alarms (id, owner_id, label, hour, minute, repeat_days_mask, one_time_date, zone_id, status, version, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)`,
      )
      .bind(id, ownerId, input.label, input.hour, input.minute, input.repeatDaysMask, input.oneTimeDate, input.zoneId, now, now),
    db
      .prepare("INSERT INTO alarm_participants (alarm_id, user_id, role, status, joined_at) VALUES (?, ?, 'OWNER', 'ACTIVE', ?)")
      .bind(id, ownerId, now),
  ]);
  return getAlarm(db, id);
}

export async function updateAlarm(db: D1Database, alarm: AlarmRow, input: AlarmInput, expectedVersion: number, now: number) {
  if (alarm.status !== "ACTIVE") throw new HttpError(410, "alarm_cancelled");
  const res = await db
    .prepare(
      `UPDATE alarms SET label = ?, hour = ?, minute = ?, repeat_days_mask = ?, one_time_date = ?, zone_id = ?,
              version = version + 1, updated_at = ?
        WHERE id = ? AND version = ?`,
    )
    .bind(input.label, input.hour, input.minute, input.repeatDaysMask, input.oneTimeDate, input.zoneId, now, alarm.id, expectedVersion)
    .run();
  if (res.meta.changes === 0) throw new HttpError(409, "version_conflict");
  return getAlarm(db, alarm.id);
}

const INVITE_ALPHABET = "ACDEFGHJKMNPQRTUVWXY34679";
export const INVITE_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export function newInviteCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return [...bytes].map((b) => INVITE_ALPHABET[b % INVITE_ALPHABET.length]).join("");
}

/**
 * Adds [userId] to the alarm behind [code]. The group-size check and the insert are one SQL
 * statement, so concurrent joins can never push a group past its limit.
 */
export async function acceptInvite(db: D1Database, code: string, userId: string, now: number): Promise<AlarmRow> {
  const invite = await db
    .prepare("SELECT alarm_id, expires_at, revoked FROM invites WHERE code = ?")
    .bind(code.toUpperCase())
    .first<{ alarm_id: string; expires_at: number; revoked: number }>();
  if (!invite || invite.revoked) throw new HttpError(404, "invite_not_found");
  if (invite.expires_at <= now) throw new HttpError(410, "invite_expired");
  const alarm = await getAlarm(db, invite.alarm_id);
  if (alarm.status !== "ACTIVE") throw new HttpError(410, "alarm_cancelled");

  const existing = await db
    .prepare("SELECT status FROM alarm_participants WHERE alarm_id = ? AND user_id = ?")
    .bind(alarm.id, userId)
    .first<{ status: string }>();
  if (existing?.status === "ACTIVE") return alarm;
  if (existing?.status === "REMOVED") throw new HttpError(403, "removed_from_alarm");

  const limit = (await userEntitlements(db, alarm.owner_id, now)).maxGroupSize;
  const res = await db
    .prepare(
      `INSERT INTO alarm_participants (alarm_id, user_id, role, status, joined_at)
       SELECT ?1, ?2, 'MEMBER', 'ACTIVE', ?3
        WHERE (SELECT COUNT(*) FROM alarm_participants WHERE alarm_id = ?1 AND status = 'ACTIVE') < ?4
       ON CONFLICT (alarm_id, user_id) DO UPDATE SET status = 'ACTIVE', joined_at = excluded.joined_at`,
    )
    .bind(alarm.id, userId, now, limit)
    .run();
  if (res.meta.changes === 0) throw new HttpError(403, "group_full");
  return alarm;
}
