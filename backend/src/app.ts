import { Hono } from "hono";
import {
  acceptInvite,
  activeParticipants,
  alarmView,
  createAlarm,
  getAlarm,
  INVITE_TTL_MS,
  newInviteCode,
  requireOwner,
  requireParticipant,
  updateAlarm,
  type AlarmRow,
} from "./alarms";
import { newToken, SESSION_TTL_MS, sessionFromRequest, sha256Hex, type GoogleVerifier, type Session } from "./auth";
import { userEntitlements } from "./entitlements";
import type { Env } from "./env";
import { ingestEvent, occurrenceStatus, parseEvent, type AcceptedChange, type EventResult } from "./events";
import type { PushMessage, PushSender } from "./push";
import { HttpError, isUuid, parseAlarmInput, parseOccurrenceId } from "./validation";

export interface Deps {
  verifyGoogle: (env: Env) => GoogleVerifier;
  push: (env: Env) => PushSender;
  now: () => number;
}

type AppEnv = { Bindings: Env; Variables: { session: Session } };

const MAX_EVENTS_PER_BATCH = 100;

export function createApp(deps: Deps) {
  const app = new Hono<AppEnv>();

  app.onError((err, c) => {
    if (err instanceof HttpError) return c.json({ error: err.code }, err.status);
    console.error(err);
    return c.json({ error: "internal" }, 500);
  });

  app.get("/health", (c) => c.json({ ok: true }));

  // ---- Auth -------------------------------------------------------------------------------

  app.post("/v1/auth/google", async (c) => {
    const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
    const idToken = typeof body.idToken === "string" ? body.idToken : "";
    const deviceId = body.deviceId;
    if (!idToken || !isUuid(deviceId)) throw new HttpError(400, "invalid_body");
    const identity = await deps.verifyGoogle(c.env)(idToken);
    const now = deps.now();
    const db = c.env.DB;

    let user = await db.prepare("SELECT id, display_name FROM users WHERE google_sub = ?").bind(identity.sub)
      .first<{ id: string; display_name: string }>();
    if (!user) {
      const id = crypto.randomUUID();
      const name = (identity.name ?? identity.email?.split("@")[0] ?? "SyncWake user").slice(0, 40);
      await db
        .prepare("INSERT INTO users (id, google_sub, email, display_name, photo_url, created_at) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(id, identity.sub, identity.email, name, identity.picture, now)
        .run();
      user = { id, display_name: name };
    }
    const token = newToken();
    const platform = typeof body.platform === "string" ? body.platform.slice(0, 20) : "android";
    const appVersion = typeof body.appVersion === "string" ? body.appVersion.slice(0, 40) : null;
    await db.batch([
      db
        .prepare(
          `INSERT INTO devices (id, user_id, platform, app_version, last_seen_at, created_at) VALUES (?, ?, ?, ?, ?, ?)
           ON CONFLICT (id) DO UPDATE SET user_id = excluded.user_id, app_version = excluded.app_version,
                  last_seen_at = excluded.last_seen_at`,
        )
        .bind(deviceId, user.id, platform, appVersion, now, now),
      db
        .prepare("INSERT INTO sessions (token_hash, user_id, device_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
        .bind(await sha256Hex(token), user.id, deviceId, now, now + SESSION_TTL_MS),
    ]);
    return c.json({ token, expiresAt: now + SESSION_TTL_MS, user: { id: user.id, displayName: user.display_name } });
  });

  app.use("/v1/*", async (c, next) => {
    if (c.req.path === "/v1/auth/google") return next();
    c.set("session", await sessionFromRequest(c.env.DB, c.req.header("Authorization"), deps.now()));
    await next();
  });

  app.post("/v1/auth/logout", async (c) => {
    const token = c.req.header("Authorization")!.slice(7).trim();
    await c.env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(await sha256Hex(token)).run();
    return c.body(null, 204);
  });

  // ---- Me / devices -----------------------------------------------------------------------

  app.get("/v1/me", async (c) => {
    const { userId } = c.get("session");
    const user = await c.env.DB.prepare("SELECT id, display_name, email FROM users WHERE id = ?").bind(userId)
      .first<{ id: string; display_name: string; email: string | null }>();
    const entitlements = await userEntitlements(c.env.DB, userId, deps.now());
    return c.json({ id: user!.id, displayName: user!.display_name, email: user!.email, entitlements });
  });

  app.put("/v1/devices/current/push-token", async (c) => {
    const { deviceId } = c.get("session");
    const body = await c.req.json<{ fcmToken?: unknown }>().catch(() => ({ fcmToken: undefined }));
    const token = typeof body.fcmToken === "string" && body.fcmToken.length <= 4096 ? body.fcmToken : null;
    await c.env.DB.prepare("UPDATE devices SET fcm_token = ?, last_seen_at = ? WHERE id = ?").bind(token, deps.now(), deviceId).run();
    return c.body(null, 204);
  });

  // ---- Alarms -----------------------------------------------------------------------------

  app.get("/v1/alarms", async (c) => {
    const { userId } = c.get("session");
    const { results } = await c.env.DB
      .prepare(
        `SELECT a.* FROM alarms a JOIN alarm_participants p ON p.alarm_id = a.id
          WHERE p.user_id = ? AND p.status = 'ACTIVE' ORDER BY a.created_at`,
      )
      .bind(userId)
      .all<AlarmRow>();
    const now = deps.now();
    return c.json({ alarms: await Promise.all(results.map((a) => alarmView(c.env.DB, a, userId, now))) });
  });

  app.post("/v1/alarms", async (c) => {
    const { userId } = c.get("session");
    const input = parseAlarmInput(await c.req.json().catch(() => null));
    const alarm = await createAlarm(c.env.DB, userId, input, deps.now());
    return c.json(await alarmView(c.env.DB, alarm, userId, deps.now()), 201);
  });

  app.get("/v1/alarms/:id", async (c) => {
    const { userId } = c.get("session");
    const id = c.req.param("id");
    await requireParticipant(c.env.DB, id, userId);
    return c.json(await alarmView(c.env.DB, await getAlarm(c.env.DB, id), userId, deps.now()));
  });

  app.put("/v1/alarms/:id", async (c) => {
    const { userId, deviceId } = c.get("session");
    const alarm = await requireOwner(c.env.DB, c.req.param("id"), userId);
    const body = await c.req.json<Record<string, unknown>>().catch(() => null);
    const expectedVersion = body?.expectedVersion;
    if (!Number.isInteger(expectedVersion)) throw new HttpError(400, "expected_version_required");
    const updated = await updateAlarm(c.env.DB, alarm, parseAlarmInput(body), expectedVersion as number, deps.now());
    c.executionCtx.waitUntil(notify(c.env, updated.id, { type: "alarm_changed", alarmId: updated.id }, deviceId));
    return c.json(await alarmView(c.env.DB, updated, userId, deps.now()));
  });

  app.delete("/v1/alarms/:id", async (c) => {
    const { userId, deviceId } = c.get("session");
    const alarm = await requireOwner(c.env.DB, c.req.param("id"), userId);
    await c.env.DB.prepare("UPDATE alarms SET status = 'CANCELLED', version = version + 1, updated_at = ? WHERE id = ?")
      .bind(deps.now(), alarm.id).run();
    c.executionCtx.waitUntil(notify(c.env, alarm.id, { type: "alarm_cancelled", alarmId: alarm.id }, deviceId));
    return c.body(null, 204);
  });

  // ---- Invites and membership ----------------------------------------------------------------

  app.post("/v1/alarms/:id/invites", async (c) => {
    const { userId } = c.get("session");
    const alarm = await requireOwner(c.env.DB, c.req.param("id"), userId);
    if (alarm.status !== "ACTIVE") throw new HttpError(410, "alarm_cancelled");
    const now = deps.now();
    const code = newInviteCode();
    await c.env.DB.prepare("INSERT INTO invites (code, alarm_id, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
      .bind(code, alarm.id, userId, now, now + INVITE_TTL_MS).run();
    return c.json({ code, url: `${c.env.INVITE_BASE_URL}${code}`, expiresAt: now + INVITE_TTL_MS }, 201);
  });

  app.post("/v1/invites/:code/accept", async (c) => {
    const { userId, deviceId } = c.get("session");
    const alarm = await acceptInvite(c.env.DB, c.req.param("code"), userId, deps.now());
    c.executionCtx.waitUntil(notify(c.env, alarm.id, { type: "alarm_changed", alarmId: alarm.id }, deviceId));
    return c.json(await alarmView(c.env.DB, alarm, userId, deps.now()));
  });

  app.post("/v1/alarms/:id/leave", async (c) => {
    const { userId, deviceId } = c.get("session");
    const id = c.req.param("id");
    const role = await requireParticipant(c.env.DB, id, userId);
    if (role === "OWNER") throw new HttpError(400, "owner_cannot_leave");
    await c.env.DB.prepare("UPDATE alarm_participants SET status = 'LEFT' WHERE alarm_id = ? AND user_id = ?").bind(id, userId).run();
    c.executionCtx.waitUntil(notify(c.env, id, { type: "alarm_changed", alarmId: id }, deviceId));
    return c.body(null, 204);
  });

  app.delete("/v1/alarms/:id/participants/:userId", async (c) => {
    const { userId, deviceId } = c.get("session");
    const alarm = await requireOwner(c.env.DB, c.req.param("id"), userId);
    const target = c.req.param("userId");
    if (target === userId) throw new HttpError(400, "owner_cannot_leave");
    const res = await c.env.DB
      .prepare("UPDATE alarm_participants SET status = 'REMOVED' WHERE alarm_id = ? AND user_id = ? AND status = 'ACTIVE'")
      .bind(alarm.id, target).run();
    if (res.meta.changes === 0) throw new HttpError(404, "participant_not_found");
    c.executionCtx.waitUntil(
      Promise.all([
        pushToUsers(c.env, [target], { type: "removed_from_alarm", alarmId: alarm.id }),
        notify(c.env, alarm.id, { type: "alarm_changed", alarmId: alarm.id }, deviceId),
      ]),
    );
    return c.body(null, 204);
  });

  // ---- Events and live status -----------------------------------------------------------------

  app.post("/v1/events", async (c) => {
    const { userId, deviceId } = c.get("session");
    const body = await c.req.json<{ events?: unknown }>().catch(() => ({ events: undefined }));
    if (!Array.isArray(body.events) || body.events.length > MAX_EVENTS_PER_BATCH) throw new HttpError(400, "invalid_body");
    const now = deps.now();
    const results: EventResult[] = [];
    const changes: AcceptedChange[] = [];
    for (const raw of body.events) {
      const parsed = parseEvent(raw);
      if ("reason" in parsed) {
        results.push({ eventId: parsed.eventId, result: "rejected", reason: parsed.reason });
        continue;
      }
      const { result, change } = await ingestEvent(c.env.DB, userId, deviceId, parsed, now);
      results.push(result);
      if (change) changes.push(change);
    }
    if (changes.length > 0) c.executionCtx.waitUntil(fanOut(c.env, changes, deviceId));
    return c.json({ results });
  });

  app.get("/v1/occurrences/:id", async (c) => {
    const { userId } = c.get("session");
    const id = c.req.param("id");
    const occ = parseOccurrenceId(id);
    if (!occ) throw new HttpError(400, "invalid_occurrence");
    await requireParticipant(c.env.DB, occ.alarmId, userId);
    return c.json(await occurrenceStatus(c.env.DB, occ.alarmId, id));
  });

  app.get("/v1/alarms/:id/live", async (c) => {
    const { userId } = c.get("session");
    const id = c.req.param("id");
    await requireParticipant(c.env.DB, id, userId);
    if (c.req.header("Upgrade") !== "websocket") throw new HttpError(400, "websocket_required");
    return room(c.env, id).fetch(c.req.raw);
  });

  // ---- Fan-out helpers ------------------------------------------------------------------------

  function room(env: Env, alarmId: string) {
    return env.ALARM_ROOM.get(env.ALARM_ROOM.idFromName(alarmId));
  }

  async function pushToUsers(env: Env, userIds: string[], message: PushMessage, excludeDevice?: string) {
    if (userIds.length === 0) return;
    const placeholders = userIds.map(() => "?").join(",");
    const { results } = await env.DB
      .prepare(`SELECT id, fcm_token FROM devices WHERE fcm_token IS NOT NULL AND user_id IN (${placeholders})`)
      .bind(...userIds)
      .all<{ id: string; fcm_token: string }>();
    const tokens = results.filter((d) => d.id !== excludeDevice).map((d) => d.fcm_token);
    try {
      const invalid = await deps.push(env).send(tokens, message);
      for (const token of invalid) {
        await env.DB.prepare("UPDATE devices SET fcm_token = NULL WHERE fcm_token = ?").bind(token).run();
      }
    } catch (e) {
      console.error("push failed", e);
    }
  }

  async function notify(env: Env, alarmId: string, message: PushMessage, excludeDevice?: string) {
    const participants = await activeParticipants(env.DB, alarmId);
    await Promise.all([
      pushToUsers(env, participants.map((p) => p.user_id), message, excludeDevice),
      room(env, alarmId).fetch("https://room/broadcast", { method: "POST", body: JSON.stringify(message) }),
    ]);
  }

  async function fanOut(env: Env, changes: AcceptedChange[], excludeDevice: string) {
    const seen = new Set<string>();
    for (const ch of changes) {
      const key = `${ch.occurrenceId}`;
      if (seen.has(key)) continue;
      seen.add(key);
      const status = await occurrenceStatus(env.DB, ch.alarmId, ch.occurrenceId);
      await room(env, ch.alarmId).fetch("https://room/broadcast", {
        method: "POST",
        body: JSON.stringify({ type: "participant_status", ...status }),
      });
      const participants = await activeParticipants(env.DB, ch.alarmId);
      await pushToUsers(
        env,
        participants.map((p) => p.user_id),
        { type: "participant_status", alarmId: ch.alarmId, occurrenceId: ch.occurrenceId },
        excludeDevice,
      );
    }
  }

  return app;
}
