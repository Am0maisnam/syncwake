/** Calendar and time-zone helpers. All instants are epoch milliseconds (UTC). */

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

export interface LocalDate {
  year: number;
  month: number; // 1-12
  day: number;
}

export function parseLocalDate(s: string): LocalDate | null {
  const m = DATE_RE.exec(s);
  if (!m) return null;
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const d = new Date(Date.UTC(year, month - 1, day));
  if (d.getUTCFullYear() !== year || d.getUTCMonth() !== month - 1 || d.getUTCDate() !== day) return null;
  return { year, month, day };
}

/** ISO day of week: Monday = 1 ... Sunday = 7. Independent of time zone. */
export function isoDayOfWeek(date: LocalDate): number {
  const js = new Date(Date.UTC(date.year, date.month - 1, date.day)).getUTCDay(); // Sunday = 0
  return js === 0 ? 7 : js;
}

export function isValidZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

/** Offset (ms) of [zone] from UTC at instant [utcMs]. */
function zoneOffset(utcMs: number, zone: string): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(new Date(utcMs));
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const asUtc = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"), get("second"));
  return asUtc - Math.floor(utcMs / 1000) * 1000;
}

/**
 * The instant at which local wall time [date] [hour]:[minute] occurs in [zone]. Matches the
 * client's java.time rules closely enough for plausibility checks: in a DST gap the result is
 * shifted forward by the gap; in an overlap the earlier offset wins.
 */
export function zonedTimeToUtc(date: LocalDate, hour: number, minute: number, zone: string): number {
  const wall = Date.UTC(date.year, date.month - 1, date.day, hour, minute);
  const first = wall - zoneOffset(wall, zone);
  const second = wall - zoneOffset(first, zone);
  if (first === second) return first;
  // Overlap or gap: prefer the earlier candidate that still maps to the wall time.
  const candidates = [Math.min(first, second), Math.max(first, second)];
  for (const c of candidates) if (c + zoneOffset(c, zone) === wall) return c;
  return Math.max(first, second); // gap: shift forward
}
