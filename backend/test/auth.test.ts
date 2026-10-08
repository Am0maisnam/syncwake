import { describe, expect, it } from "vitest";
import { call, googleToken, signIn } from "./helpers";

describe("auth", () => {
  it("signs in with a valid Google ID token and returns Free entitlements", async () => {
    const alex = await signIn("Alex");
    const res = await call("GET", "/v1/me", { token: alex.token });
    expect(res.status).toBe(200);
    const me = (await res.json()) as { displayName: string; entitlements: { plan: string; maxGroupSize: number; wakeRace: boolean } };
    expect(me.displayName).toBe("Alex");
    expect(me.entitlements).toMatchObject({ plan: "FREE", maxGroupSize: 3, wakeRace: false });
  });

  it("returns the same account for the same Google user on a second device", async () => {
    const first = await signIn("Rahul");
    const token = await googleToken(first.sub, { name: "Rahul" });
    const res = await call("POST", "/v1/auth/google", { body: { idToken: token, deviceId: crypto.randomUUID() } });
    expect(((await res.json()) as { user: { id: string } }).user.id).toBe(first.userId);
  });

  it("rejects a token for another app (wrong audience)", async () => {
    const res = await call("POST", "/v1/auth/google", {
      body: { idToken: await googleToken("x", { aud: "someone-else" }), deviceId: crypto.randomUUID() },
    });
    expect(res.status).toBe(401);
  });

  it("rejects an expired token", async () => {
    const res = await call("POST", "/v1/auth/google", {
      body: { idToken: await googleToken("x", { expSeconds: Math.floor(Date.now() / 1000) - 60 }), deviceId: crypto.randomUUID() },
    });
    expect(res.status).toBe(401);
  });

  it("rejects missing or forged session tokens", async () => {
    expect((await call("GET", "/v1/me")).status).toBe(401);
    expect((await call("GET", "/v1/me", { token: "forged" })).status).toBe(401);
  });

  it("logout revokes the session", async () => {
    const alex = await signIn("Alex");
    expect((await call("POST", "/v1/auth/logout", { token: alex.token })).status).toBe(204);
    expect((await call("GET", "/v1/me", { token: alex.token })).status).toBe(401);
  });
});
