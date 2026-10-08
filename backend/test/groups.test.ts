import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { call, clock, createAlarm, DAILY_7AM_NY, invite, join, signIn, signInAll } from "./helpers";

async function setPlan(userId: string, plan: "FREE" | "PREMIUM", expiresAt: number | null = null) {
  await env.DB.prepare(
    "INSERT INTO subscriptions (user_id, plan, expires_at, updated_at) VALUES (?, ?, ?, 0) ON CONFLICT (user_id) DO UPDATE SET plan = excluded.plan, expires_at = excluded.expires_at",
  ).bind(userId, plan, expiresAt).run();
}

describe("alarms and groups", () => {
  it("validates alarm input", async () => {
    const alex = await signIn("Alex");
    const bad = [
      { ...DAILY_7AM_NY, zoneId: "Mars/Olympus" },
      { ...DAILY_7AM_NY, hour: 24 },
      { ...DAILY_7AM_NY, label: "x".repeat(31) },
      { ...DAILY_7AM_NY, repeatDaysMask: 0, oneTimeDate: null },
    ];
    for (const body of bad) expect((await call("POST", "/v1/alarms", { token: alex.token, body })).status).toBe(400);
  });

  it("free owner: at most 3 people in a synced alarm, enforced by the server", async () => {
    const [alex, rahul, john, maya] = await signInAll("Alex", "Rahul", "John", "Maya");
    const alarm = await createAlarm(alex);
    const code = await invite(alex, alarm.id);
    expect((await join(rahul, code)).status).toBe(200);
    expect((await join(john, code)).status).toBe(200);
    const full = await join(maya, code);
    expect(full.status).toBe(403);
    expect(await full.json()).toEqual({ error: "group_full" });
    const view = (await (await call("GET", `/v1/alarms/${alarm.id}`, { token: alex.token })).json()) as { participants: unknown[] };
    expect(view.participants).toHaveLength(3);
  });

  it("concurrent joins can't overfill a group", async () => {
    const [alex, rahul, john, maya] = await signInAll("Alex", "Rahul", "John", "Maya");
    const alarm = await createAlarm(alex);
    const code = await invite(alex, alarm.id);
    await join(rahul, code);
    const results = await Promise.all([join(john, code), join(maya, code)]);
    expect(results.map((r) => r.status).sort()).toEqual([200, 403]);
  });

  it("premium owner can go past 3; a downgrade restricts but never removes anyone", async () => {
    const [alex, b, c, d, e] = await signInAll("Alex", "B", "C", "D", "E");
    await setPlan(alex.userId, "PREMIUM");
    const alarm = await createAlarm(alex);
    const code = await invite(alex, alarm.id);
    for (const u of [b, c, d]) expect((await join(u, code)).status).toBe(200);

    await setPlan(alex.userId, "PREMIUM", clock.now - 1); // expired -> Free
    const view = (await (await call("GET", `/v1/alarms/${alarm.id}`, { token: alex.token })).json()) as {
      restricted: boolean;
      participants: unknown[];
      maxGroupSize: number;
    };
    expect(view).toMatchObject({ restricted: true, maxGroupSize: 3 });
    expect(view.participants).toHaveLength(4);
    expect((await join(e, code)).status).toBe(403);
  });

  it("only participants can see an alarm; only the owner can change it or invite", async () => {
    const [alex, rahul, stranger] = await signInAll("Alex", "Rahul", "Stranger");
    const alarm = await createAlarm(alex);
    await join(rahul, await invite(alex, alarm.id));

    expect((await call("GET", `/v1/alarms/${alarm.id}`, { token: stranger.token })).status).toBe(404);
    expect((await call("GET", `/v1/occurrences/${alarm.id}@2026-10-09`, { token: stranger.token })).status).toBe(404);
    expect((await call("POST", `/v1/alarms/${alarm.id}/invites`, { token: rahul.token })).status).toBe(403);
    expect(
      (await call("PUT", `/v1/alarms/${alarm.id}`, { token: rahul.token, body: { ...DAILY_7AM_NY, expectedVersion: 1 } })).status,
    ).toBe(403);
    expect((await call("DELETE", `/v1/alarms/${alarm.id}`, { token: rahul.token })).status).toBe(403);
  });

  it("edits use optimistic versioning", async () => {
    const alex = await signIn("Alex");
    const alarm = await createAlarm(alex);
    const ok = await call("PUT", `/v1/alarms/${alarm.id}`, { token: alex.token, body: { ...DAILY_7AM_NY, hour: 6, expectedVersion: 1 } });
    expect(ok.status).toBe(200);
    expect(((await ok.json()) as { version: number }).version).toBe(2);
    const stale = await call("PUT", `/v1/alarms/${alarm.id}`, { token: alex.token, body: { ...DAILY_7AM_NY, expectedVersion: 1 } });
    expect(stale.status).toBe(409);
  });

  it("removed members lose access and can't rejoin with the old link", async () => {
    const [alex, rahul] = await signInAll("Alex", "Rahul");
    const alarm = await createAlarm(alex);
    const code = await invite(alex, alarm.id);
    await join(rahul, code);
    expect((await call("DELETE", `/v1/alarms/${alarm.id}/participants/${rahul.userId}`, { token: alex.token })).status).toBe(204);
    expect((await call("GET", `/v1/alarms/${alarm.id}`, { token: rahul.token })).status).toBe(404);
    expect((await join(rahul, code)).status).toBe(403);
  });

  it("members can leave and rejoin; expired invites are refused", async () => {
    const [alex, rahul, john] = await signInAll("Alex", "Rahul", "John");
    const alarm = await createAlarm(alex);
    const code = await invite(alex, alarm.id);
    await join(rahul, code);
    expect((await call("POST", `/v1/alarms/${alarm.id}/leave`, { token: rahul.token })).status).toBe(204);
    expect((await join(rahul, code)).status).toBe(200);

    clock.now += 8 * 24 * 60 * 60 * 1000;
    try {
      expect((await join(john, code)).status).toBe(410);
    } finally {
      clock.now -= 8 * 24 * 60 * 60 * 1000;
    }
  });
});
