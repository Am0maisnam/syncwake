import { importPKCS8, SignJWT } from "jose";

/** A data-only push. Clients treat it as a hint to sync; it never carries private details. */
export interface PushMessage {
  type: "alarm_changed" | "alarm_cancelled" | "participant_status" | "removed_from_alarm";
  alarmId: string;
  occurrenceId?: string;
}

export interface PushSender {
  /** Returns the tokens FCM reported as no longer valid. */
  send(tokens: string[], message: PushMessage): Promise<string[]>;
}

export const noPush: PushSender = { send: async () => [] };

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

/** Firebase Cloud Messaging HTTP v1 sender authenticated with a service account. */
export function fcmSender(serviceAccountJson: string): PushSender {
  const account = JSON.parse(serviceAccountJson) as ServiceAccount;
  let cached: { token: string; expiresAt: number } | null = null;

  async function accessToken(): Promise<string> {
    const now = Date.now();
    if (cached && cached.expiresAt - 60_000 > now) return cached.token;
    const key = await importPKCS8(account.private_key, "RS256");
    const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging" })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(account.client_email)
      .setAudience("https://oauth2.googleapis.com/token")
      .setIssuedAt()
      .setExpirationTime("1h")
      .sign(key);
    const res = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    if (!res.ok) throw new Error(`FCM auth failed: ${res.status}`);
    const json = (await res.json()) as { access_token: string; expires_in: number };
    cached = { token: json.access_token, expiresAt: now + json.expires_in * 1000 };
    return json.access_token;
  }

  return {
    async send(tokens, message) {
      if (tokens.length === 0) return [];
      const bearer = await accessToken();
      const data: Record<string, string> = { type: message.type, alarmId: message.alarmId };
      if (message.occurrenceId) data.occurrenceId = message.occurrenceId;
      const invalid: string[] = [];
      await Promise.all(
        tokens.map(async (token) => {
          const res = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
            method: "POST",
            headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
            body: JSON.stringify({
              message: { token, data, android: { priority: "high", collapse_key: message.occurrenceId ?? message.alarmId } },
            }),
          });
          if (res.status === 404 || res.status === 400) {
            const text = await res.text();
            if (text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT")) invalid.push(token);
          }
        }),
      );
      return invalid;
    },
  };
}
