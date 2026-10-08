import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT } from "jose";
import { createApp } from "../src/app";
import { googleVerifier } from "../src/auth";
import type { PushMessage, PushSender } from "../src/push";

const { publicKey, privateKey } = await generateKeyPair("RS256");
const jwk = { ...(await exportJWK(publicKey)), kid: "test-key", alg: "RS256" };
const jwks = createLocalJWKSet({ keys: [jwk] });

export const clock = { now: Date.parse("2026-10-09T11:01:00Z") }; // 07:01 in New York

export const pushes: { tokens: string[]; message: PushMessage }[] = [];
const recordingPush: PushSender = {
  async send(tokens, message) {
    if (tokens.length > 0) pushes.push({ tokens, message });
    return [];
  },
};

export const app = createApp({
  verifyGoogle: () => googleVerifier(["test-client-id"], jwks),
  push: () => recordingPush,
  now: () => clock.now,
});

export async function googleToken(sub: string, overrides: { aud?: string; expSeconds?: number; name?: string } = {}) {
  return new SignJWT({ name: overrides.name ?? sub, email: `${sub}@example.com`, email_verified: true })
    .setProtectedHeader({ alg: "RS256", kid: "test-key" })
    .setIssuer("https://accounts.google.com")
    .setAudience(overrides.aud ?? "test-client-id")
    .setSubject(sub)
    .setIssuedAt()
    .setExpirationTime(overrides.expSeconds ?? Math.floor(Date.now() / 1000) + 3600)
    .sign(privateKey);
}

export async function call(method: string, path: string, opts: { token?: string; body?: unknown; headers?: Record<string, string> } = {}) {
  const headers: Record<string, string> = { ...opts.headers };
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  if (opts.body !== undefined) headers["content-type"] = "application/json";
  const ctx = createExecutionContext();
  const res = await app.fetch(
    new Request(`https://api.test${path}`, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    }),
    env,
    ctx,
  );
  await waitOnExecutionContext(ctx);
  return res;
}

export interface TestUser {
  token: string;
  userId: string;
  deviceId: string;
  name: string;
  sub: string;
}

/** Signs in several users at once, typed as a fixed-length tuple. */
export async function signInAll<const N extends readonly string[]>(...names: N): Promise<{ [K in keyof N]: TestUser }> {
  const users: TestUser[] = [];
  for (const n of names) users.push(await signIn(n));
  return users as { [K in keyof N]: TestUser };
}

let counter = 0;
export async function signIn(name: string, deviceId: string = crypto.randomUUID()): Promise<TestUser> {
  const sub = `${name}-${++counter}-${crypto.randomUUID()}`;
  const res = await call("POST", "/v1/auth/google", { body: { idToken: await googleToken(sub, { name }), deviceId } });
  if (res.status !== 200) throw new Error(`sign-in failed: ${res.status} ${await res.text()}`);
  const json = (await res.json()) as { token: string; user: { id: string } };
  return { token: json.token, userId: json.user.id, deviceId, name, sub };
}

/** Second device for an existing account (same Google subject). */
export async function signInAgain(sub: string, name: string): Promise<TestUser> {
  const deviceId = crypto.randomUUID();
  const res = await call("POST", "/v1/auth/google", { body: { idToken: await googleToken(sub, { name }), deviceId } });
  const json = (await res.json()) as { token: string; user: { id: string } };
  return { token: json.token, userId: json.user.id, deviceId, name, sub };
}

export const DAILY_7AM_NY = {
  label: "Morning run",
  hour: 7,
  minute: 0,
  repeatDaysMask: 127,
  oneTimeDate: null,
  zoneId: "America/New_York",
};

export async function createAlarm(owner: TestUser, input: Record<string, unknown> = DAILY_7AM_NY) {
  const res = await call("POST", "/v1/alarms", { token: owner.token, body: input });
  if (res.status !== 201) throw new Error(`create failed: ${res.status} ${await res.text()}`);
  return (await res.json()) as { id: string; version: number };
}

export async function invite(owner: TestUser, alarmId: string): Promise<string> {
  const res = await call("POST", `/v1/alarms/${alarmId}/invites`, { token: owner.token });
  return ((await res.json()) as { code: string }).code;
}

export async function join(user: TestUser, code: string) {
  return call("POST", `/v1/invites/${code}/accept`, { token: user.token });
}

export function event(occurrenceId: string, type: string, extra: Record<string, unknown> = {}) {
  return { eventId: crypto.randomUUID(), occurrenceId, type, clientAt: clock.now, ...extra };
}

export async function sendEvents(user: TestUser, events: unknown[]) {
  const res = await call("POST", "/v1/events", { token: user.token, body: { events } });
  return ((await res.json()) as { results: { eventId: string; result: string; reason?: string }[] }).results;
}

export async function statusOf(user: TestUser, occurrenceId: string) {
  const res = await call("GET", `/v1/occurrences/${occurrenceId}`, { token: user.token });
  return res;
}
