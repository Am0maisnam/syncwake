import { describe, expect, it } from "vitest";
import { effectivePlan, entitlementsFor } from "../src/entitlements";
import { isoDayOfWeek, parseLocalDate, zonedTimeToUtc } from "../src/time";
import { parseOccurrenceId } from "../src/validation";

describe("time", () => {
  const d = (s: string) => parseLocalDate(s)!;

  it("converts local alarm times to UTC across DST", () => {
    expect(new Date(zonedTimeToUtc(d("2026-10-09"), 7, 0, "America/New_York")).toISOString()).toBe("2026-10-09T11:00:00.000Z");
    expect(new Date(zonedTimeToUtc(d("2026-01-09"), 7, 0, "America/New_York")).toISOString()).toBe("2026-01-09T12:00:00.000Z");
    // Spring-forward gap: 02:30 doesn't exist, shifted to 03:30 EDT.
    expect(new Date(zonedTimeToUtc(d("2026-03-08"), 2, 30, "America/New_York")).toISOString()).toBe("2026-03-08T07:30:00.000Z");
    // Fall-back overlap: 01:30 happens twice, the earlier (EDT) one wins.
    expect(new Date(zonedTimeToUtc(d("2026-11-01"), 1, 30, "America/New_York")).toISOString()).toBe("2026-11-01T05:30:00.000Z");
    expect(new Date(zonedTimeToUtc(d("2026-10-09"), 7, 0, "Asia/Kolkata")).toISOString()).toBe("2026-10-09T01:30:00.000Z");
  });

  it("parses dates strictly and computes ISO weekdays", () => {
    expect(parseLocalDate("2026-02-30")).toBeNull();
    expect(parseLocalDate("2026-1-1")).toBeNull();
    expect(isoDayOfWeek(d("2026-10-09"))).toBe(5); // Friday
    expect(isoDayOfWeek(d("2026-10-11"))).toBe(7); // Sunday
  });

  it("parses occurrence ids", () => {
    const id = crypto.randomUUID();
    expect(parseOccurrenceId(`${id}@2026-10-09`)).toEqual({ alarmId: id, date: "2026-10-09" });
    expect(parseOccurrenceId("abc@2026-10-09")).toBeNull();
    expect(parseOccurrenceId(`${id}@tomorrow`)).toBeNull();
  });
});

describe("entitlements", () => {
  it("is server-authoritative and expires", () => {
    expect(effectivePlan(null, 0)).toBe("FREE");
    expect(effectivePlan({ plan: "PREMIUM", expires_at: null }, 0)).toBe("PREMIUM");
    expect(effectivePlan({ plan: "PREMIUM", expires_at: 100 }, 99)).toBe("PREMIUM");
    expect(effectivePlan({ plan: "PREMIUM", expires_at: 100 }, 100)).toBe("FREE");
    expect(entitlementsFor("FREE")).toMatchObject({ maxGroupSize: 3, wakeRace: false, sleepSync: false, voiceAlarm: false });
    expect(entitlementsFor("PREMIUM")).toMatchObject({ maxGroupSize: 100, wakeRace: true });
  });
});
