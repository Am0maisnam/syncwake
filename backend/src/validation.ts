import { isValidZone, parseLocalDate } from "./time";

export class HttpError extends Error {
  constructor(
    readonly status: 400 | 401 | 403 | 404 | 409 | 410 | 429,
    readonly code: string,
    message?: string,
  ) {
    super(message ?? code);
  }
}

export interface AlarmInput {
  label: string;
  hour: number;
  minute: number;
  repeatDaysMask: number;
  oneTimeDate: string | null;
  zoneId: string;
}

const LABEL_MAX = 30;

export function parseAlarmInput(body: unknown): AlarmInput {
  if (typeof body !== "object" || body === null) throw new HttpError(400, "invalid_body");
  const b = body as Record<string, unknown>;
  const label = typeof b.label === "string" ? b.label.trim() : "";
  if (label.length > LABEL_MAX) throw new HttpError(400, "label_too_long");
  const hour = b.hour;
  const minute = b.minute;
  if (!Number.isInteger(hour) || (hour as number) < 0 || (hour as number) > 23) throw new HttpError(400, "invalid_hour");
  if (!Number.isInteger(minute) || (minute as number) < 0 || (minute as number) > 59) throw new HttpError(400, "invalid_minute");
  const mask = b.repeatDaysMask ?? 0;
  if (!Number.isInteger(mask) || (mask as number) < 0 || (mask as number) > 127) throw new HttpError(400, "invalid_repeat_days");
  const oneTimeDate = b.oneTimeDate == null ? null : String(b.oneTimeDate);
  if (mask === 0) {
    if (oneTimeDate == null || parseLocalDate(oneTimeDate) == null) throw new HttpError(400, "one_time_date_required");
  }
  const zoneId = typeof b.zoneId === "string" ? b.zoneId : "";
  if (!isValidZone(zoneId)) throw new HttpError(400, "invalid_zone");
  return {
    label,
    hour: hour as number,
    minute: minute as number,
    repeatDaysMask: mask as number,
    oneTimeDate: mask === 0 ? oneTimeDate : null,
    zoneId,
  };
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (s: unknown): s is string => typeof s === "string" && UUID_RE.test(s);

/** "<alarmId>@<YYYY-MM-DD>" — the deterministic occurrence id every device derives. */
export function parseOccurrenceId(id: string): { alarmId: string; date: string } | null {
  const at = id.lastIndexOf("@");
  if (at <= 0) return null;
  const alarmId = id.slice(0, at);
  const date = id.slice(at + 1);
  if (!isUuid(alarmId) || parseLocalDate(date) == null) return null;
  return { alarmId, date };
}
