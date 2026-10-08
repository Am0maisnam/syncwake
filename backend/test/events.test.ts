import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  call,
  clock,
  createAlarm,
  event,
  invite,
  join,
  pushes,
  sendEvents,
  signIn,
  signInAgain,
  statusOf,
  type TestUser,
} from "./helpers";

const TODAY = "2026-10-09";

async function group(names: string[]) {
  const users = await Promise.all(names.map((n) => signIn(n)));
  const owner = users[0]!;
  const alarm = await createAlarm(owner);
  const code = await invite(owner, alarm.id);
  for (const u of users.slice(1)) await join(u, code);
  return { users, occ: `${alarm.id}@${TODAY}`, alarmId: alarm.id };
}

async function statuses(viewer: TestUser, occ: string) {
  const body = (await (await statusOf(viewer, occ)).json()) as {
    awake: number;
    total: number;
    participants: { displayName: string; status: string }[];
  };
  return { ...body, byName: Object.fromEntries(body.participants.map((p) => [p.displayName, p.status])) };
}

describe("events and per-user completion", () => {
  it("each participant completes their own alarm", async () => {
    const { users, occ } = await group(["Alex", "Rahul", "John"]);
    const [alex, rahul] = users as [TestUser, TestUser];
    expect(await sendEvents(rahul, [event(occ, "STATUS_CHANGED", { status: "RINGING" })])).toMatchObject([{ result: "accepted" }]);
    expect(await sendEvents(alex, [event(occ, "ALARM_COMPLETED")])).toMatchObject([{ result: "accepted" }]);

    const s = await statuses(alex, occ);
    expect(s.byName).toEqual({ Alex: "WAKE_VERIFICATION", Rahul: "RINGING", John: "SCHEDULED" });
    expect(s).toMatchObject({ awake: 0, total: 3 });

    await sendEvents(alex, [event(occ, "WAKE_PROOF_COMPLETED")]);
    expect((await statuses(rahul, occ)).awake).toBe(1);
  });

  it("is idempotent: a replayed event changes nothing", async () => {
    const { users, occ } = await group(["Alex", "Rahul"]);
    const [alex, rahul] = users as [TestUser, TestUser];
    const e = event(occ, "ALARM_COMPLETED");
    expect(await sendEvents(alex, [e])).toMatchObject([{ result: "accepted" }]);
    expect(await sendEvents(alex, [e])).toMatchObject([{ result: "duplicate" }]);
    // Another user can't replay someone else's event id.
    expect(await sendEvents(rahul, [{ ...e }])).toMatchObject([{ result: "rejected", reason: "event_id_conflict" }]);
    const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM completions WHERE occurrence_id = ?").bind(occ).first<{ n: number }>();
    expect(count!.n).toBe(1);
  });

  it("multiple devices: only the first completion per user counts", async () => {
    const { users, occ } = await group(["Alex", "Rahul"]);
    const alex = users[0]!;
    const alexTablet = await signInAgain(alex.sub, "Alex");
    expect(alexTablet.userId).toBe(alex.userId);

    await sendEvents(alex, [event(occ, "ALARM_COMPLETED")]);
    clock.now += 5_000;
    try {
      expect(await sendEvents(alexTablet, [event(occ, "ALARM_COMPLETED")])).toMatchObject([{ result: "accepted" }]);
    } finally {
      clock.now -= 5_000;
    }
    const rows = await env.DB.prepare("SELECT device_id FROM completions WHERE occurrence_id = ? AND user_id = ?")
      .bind(occ, alex.userId).all<{ device_id: string }>();
    expect(rows.results).toEqual([{ device_id: alex.deviceId }]);
  });

  it("simultaneous completions get a deterministic server order", async () => {
    const { users, occ } = await group(["Alex", "Rahul", "John"]);
    for (const u of users) await sendEvents(u, [event(occ, "ALARM_COMPLETED")]); // same server time
    const rows = await env.DB.prepare("SELECT user_id FROM completions WHERE occurrence_id = ? ORDER BY server_received_at, seq")
      .bind(occ).all<{ user_id: string }>();
    expect(rows.results.map((r) => r.user_id)).toEqual(users.map((u) => u.userId));
  });

  it("offline completions sync later, but stale, future and impossible events are refused", async () => {
    const { users, alarmId } = await group(["Alex"]);
    const alex = users[0]!;
    const yesterday = `${alarmId}@2026-10-08`;
    // Completed yesterday at 07:03 while offline; uploaded today.
    expect(await sendEvents(alex, [event(yesterday, "ALARM_COMPLETED", { clientAt: Date.parse("2026-10-08T11:03:00Z") })]))
      .toMatchObject([{ result: "accepted" }]);

    const results = await sendEvents(alex, [
      event(`${alarmId}@2026-09-30`, "ALARM_COMPLETED", { clientAt: Date.parse("2026-09-30T11:03:00Z") }),
      event(`${alarmId}@${TODAY}`, "ALARM_COMPLETED", { clientAt: clock.now + 10 * 60_000 }),
      event(`${alarmId}@${TODAY}`, "ALARM_COMPLETED", { clientAt: Date.parse("2026-10-09T10:00:00Z") }),
      event(`${alarmId}@2026-02-30`, "ALARM_COMPLETED"),
      event(`${crypto.randomUUID()}@${TODAY}`, "ALARM_COMPLETED"),
      event(`${alarmId}@${TODAY}`, "STATUS_CHANGED", { status: "PHONE_MOVING" }),
      { ...event(`${alarmId}@${TODAY}`, "ALARM_COMPLETED"), eventId: "not-a-uuid" },
    ]);
    expect(results.map((r) => r.reason)).toEqual([
      "stale",
      "clock_ahead",
      "before_alarm",
      "invalid_occurrence",
      "not_participant",
      "invalid_status",
      "invalid_event_id",
    ]);
  });

  it("rejects events for days the alarm doesn't ring", async () => {
    const alex = await signIn("Alex");
    const weekdays = await createAlarm(alex, { label: "", hour: 7, minute: 0, repeatDaysMask: 0b0011111, oneTimeDate: null, zoneId: "America/New_York" });
    // 2026-10-10 is a Saturday.
    clock.now = Date.parse("2026-10-10T11:01:00Z");
    try {
      expect(await sendEvents(alex, [event(`${weekdays.id}@2026-10-10`, "ALARM_COMPLETED")])).toMatchObject([
        { result: "rejected", reason: "not_scheduled" },
      ]);
    } finally {
      clock.now = Date.parse("2026-10-09T11:01:00Z");
    }
  });

  it("out-of-order uploads never move a status backwards", async () => {
    const { users, occ } = await group(["Alex"]);
    const alex = users[0]!;
    await sendEvents(alex, [
      event(occ, "WAKE_PROOF_COMPLETED", { clientAt: clock.now }),
      event(occ, "STATUS_CHANGED", { status: "RINGING", clientAt: clock.now - 30_000 }),
    ]);
    expect((await statuses(alex, occ)).byName.Alex).toBe("AWAKE_CONFIRMED");
  });

  it("removed participants can't post events", async () => {
    const { users, occ, alarmId } = await group(["Alex", "Rahul"]);
    const [alex, rahul] = users as [TestUser, TestUser];
    await call("DELETE", `/v1/alarms/${alarmId}/participants/${rahul.userId}`, { token: alex.token });
    expect(await sendEvents(rahul, [event(occ, "ALARM_COMPLETED")])).toMatchObject([{ result: "rejected", reason: "not_participant" }]);
  });

  it("privacy: unknown fields such as raw activity are dropped, never stored or shared", async () => {
    const { users, occ } = await group(["Alex", "Rahul"]);
    const [alex, rahul] = users as [TestUser, TestUser];
    await sendEvents(alex, [
      event(occ, "STATUS_CHANGED", { status: "WAKE_VERIFICATION", accelerometer: [0.1, 9.8, 0.2], screenOn: true, unlockedAt: clock.now }),
    ]);
    const raw = JSON.stringify(await (await statusOf(rahul, occ)).json());
    for (const word of ["accelerometer", "screenOn", "unlocked", "clientAt", "deviceId"]) expect(raw).not.toContain(word);
    const stored = await env.DB.prepare("SELECT * FROM sync_events WHERE occurrence_id = ?").bind(occ).first();
    expect(Object.keys(stored!)).not.toContain("payload");
    expect(JSON.stringify(stored)).not.toContain("9.8");
  });

  it("pushes status changes to the other participants' devices, not the sender's", async () => {
    const { users, occ } = await group(["Alex", "Rahul"]);
    const [alex, rahul] = users as [TestUser, TestUser];
    await call("PUT", "/v1/devices/current/push-token", { token: alex.token, body: { fcmToken: "alex-token" } });
    await call("PUT", "/v1/devices/current/push-token", { token: rahul.token, body: { fcmToken: "rahul-token" } });
    pushes.length = 0;
    await sendEvents(rahul, [event(occ, "ALARM_COMPLETED")]);
    expect(pushes).toEqual([
      { tokens: ["alex-token"], message: { type: "participant_status", alarmId: occ.split("@")[0], occurrenceId: occ } },
    ]);
  });

  it("streams live status to connected participants over WebSocket", async () => {
    const { users, occ, alarmId } = await group(["Alex", "Rahul"]);
    const [alex, rahul] = users as [TestUser, TestUser];
    const res = await call("GET", `/v1/alarms/${alarmId}/live`, { token: alex.token, headers: { Upgrade: "websocket" } });
    expect(res.status).toBe(101);
    const ws = res.webSocket!;
    ws.accept();
    const received = new Promise<string>((resolve) => ws.addEventListener("message", (m) => resolve(m.data as string)));
    await sendEvents(rahul, [event(occ, "STATUS_CHANGED", { status: "RINGING" })]);
    const msg = JSON.parse(await received) as { type: string; participants: { displayName: string; status: string }[] };
    expect(msg.type).toBe("participant_status");
    expect(msg.participants.find((p) => p.displayName === "Rahul")?.status).toBe("RINGING");
    ws.close();
  });

  it("strangers can't open the live stream", async () => {
    const { alarmId } = await group(["Alex"]);
    const stranger = await signIn("Stranger");
    const res = await call("GET", `/v1/alarms/${alarmId}/live`, { token: stranger.token, headers: { Upgrade: "websocket" } });
    expect(res.status).toBe(404);
  });
});
