import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";
import { HttpError } from "./validation";

export interface GoogleIdentity {
  sub: string;
  email: string | null;
  name: string | null;
  picture: string | null;
}

export type GoogleVerifier = (idToken: string) => Promise<GoogleIdentity>;

const GOOGLE_ISSUERS = ["https://accounts.google.com", "accounts.google.com"];
const googleJwks = createRemoteJWKSet(new URL("https://www.googleapis.com/oauth2/v3/certs"));

/** Verifies a Google ID token's signature, issuer, audience and expiry. */
export function googleVerifier(clientIds: string[], jwks: JWTVerifyGetKey = googleJwks): GoogleVerifier {
  return async (idToken) => {
    if (clientIds.length === 0) throw new HttpError(401, "sign_in_not_configured");
    try {
      const { payload } = await jwtVerify(idToken, jwks, { issuer: GOOGLE_ISSUERS, audience: clientIds });
      if (typeof payload.sub !== "string") throw new Error("no sub");
      if (payload.email != null && payload.email_verified !== true) throw new Error("email not verified");
      return {
        sub: payload.sub,
        email: typeof payload.email === "string" ? payload.email : null,
        name: typeof payload.name === "string" ? payload.name : null,
        picture: typeof payload.picture === "string" ? payload.picture : null,
      };
    } catch (e) {
      if (e instanceof HttpError) throw e;
      throw new HttpError(401, "invalid_google_token");
    }
  };
}

export const SESSION_TTL_MS = 90 * 24 * 60 * 60 * 1000;

export function newToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(s: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export interface Session {
  userId: string;
  deviceId: string;
}

export async function sessionFromRequest(db: D1Database, authorization: string | undefined, now: number): Promise<Session> {
  const token = authorization?.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
  if (!token) throw new HttpError(401, "missing_token");
  const row = await db
    .prepare("SELECT user_id, device_id, expires_at FROM sessions WHERE token_hash = ?")
    .bind(await sha256Hex(token))
    .first<{ user_id: string; device_id: string; expires_at: number }>();
  if (!row || row.expires_at <= now) throw new HttpError(401, "invalid_token");
  return { userId: row.user_id, deviceId: row.device_id };
}
