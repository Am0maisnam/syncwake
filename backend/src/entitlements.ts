/**
 * Centralised, server-authoritative feature entitlements. Every Premium check goes through
 * [entitlementsFor]; nothing else in the codebase compares plan names.
 */
export type Plan = "FREE" | "PREMIUM";

export interface Entitlements {
  plan: Plan;
  /** Total participants (owner included) a synced alarm owned by this user may have. */
  maxGroupSize: number;
  wakeRace: boolean;
  wakeMedals: boolean;
  sleepSync: boolean;
  sleepRanking: boolean;
  sleepMedals: boolean;
  voiceAlarm: boolean;
  customAudio: boolean;
  longTermHistory: boolean;
  advancedStats: boolean;
  advancedGroups: boolean;
}

const FREE: Entitlements = {
  plan: "FREE",
  maxGroupSize: 3,
  wakeRace: false,
  wakeMedals: false,
  sleepSync: false,
  sleepRanking: false,
  sleepMedals: false,
  voiceAlarm: false,
  customAudio: false,
  longTermHistory: false,
  advancedStats: false,
  advancedGroups: false,
};

const PREMIUM: Entitlements = {
  plan: "PREMIUM",
  maxGroupSize: 100,
  wakeRace: true,
  wakeMedals: true,
  sleepSync: true,
  sleepRanking: true,
  sleepMedals: true,
  voiceAlarm: true,
  customAudio: true,
  longTermHistory: true,
  advancedStats: true,
  advancedGroups: true,
};

/** Effective plan: an expired Premium subscription is Free. */
export function effectivePlan(row: { plan: string; expires_at: number | null } | null, now: number): Plan {
  if (row?.plan === "PREMIUM" && (row.expires_at == null || row.expires_at > now)) return "PREMIUM";
  return "FREE";
}

export function entitlementsFor(plan: Plan): Entitlements {
  return plan === "PREMIUM" ? PREMIUM : FREE;
}

export async function userEntitlements(db: D1Database, userId: string, now: number): Promise<Entitlements> {
  const row = await db
    .prepare("SELECT plan, expires_at FROM subscriptions WHERE user_id = ?")
    .bind(userId)
    .first<{ plan: string; expires_at: number | null }>();
  return entitlementsFor(effectivePlan(row, now));
}
