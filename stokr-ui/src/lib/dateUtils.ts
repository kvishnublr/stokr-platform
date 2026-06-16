/** All user-facing timestamps are displayed in IST (Asia/Kolkata = UTC+5:30). */
export const IST_ZONE = "Asia/Kolkata";
export const IST_LOCALE = "en-IN";

type DateInput = string | number | Date | null | undefined;

function toDate(value: DateInput): Date | null {
  if (value === null || value === undefined) return null;
  if (value instanceof Date) return value;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function fmtTime(iso: DateInput): string {
  const d = toDate(iso);
  if (!d) return "—";
  return d.toLocaleTimeString(IST_LOCALE, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZone: IST_ZONE,
  });
}

export function fmtDate(iso: DateInput): string {
  const d = toDate(iso);
  if (!d) return "—";
  return d.toLocaleDateString(IST_LOCALE, { day: "2-digit", month: "short", timeZone: IST_ZONE });
}

export function fmtDateTime(iso: DateInput): string {
  if (!iso) return "—";
  return `${fmtTime(iso)}  ${fmtDate(iso)}`;
}

export function fmtDateLong(iso: DateInput): string {
  const d = toDate(iso);
  if (!d) return "—";
  return d.toLocaleDateString(IST_LOCALE, { weekday: "long", day: "numeric", month: "long", timeZone: IST_ZONE });
}

export function fmtDateWeekday(iso: DateInput): string {
  const d = toDate(iso);
  if (!d) return "—";
  return d.toLocaleDateString(IST_LOCALE, { weekday: "short", month: "short", day: "numeric", timeZone: IST_ZONE });
}

export function fmtDateRange(from: Date, to: Date): string {
  const opts: Intl.DateTimeFormatOptions = { month: "short", day: "numeric", year: "numeric", timeZone: IST_ZONE };
  return `${from.toLocaleDateString(IST_LOCALE, opts)} → ${to.toLocaleDateString(IST_LOCALE, opts)}`;
}

/** Live header: `NSE · Tue 26 May · 10:45 IST` */
export function fmtNseClock(at: Date = new Date()): string {
  const day = at.toLocaleDateString(IST_LOCALE, {
    weekday: "short",
    day: "numeric",
    month: "short",
    timeZone: IST_ZONE,
  });
  const time = at.toLocaleTimeString(IST_LOCALE, {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    timeZone: IST_ZONE,
  });
  return `NSE · ${day} · ${time} IST`;
}

/** Aliases for callers that prefer explicit naming. */
export const formatIstDateTime = fmtDateTime;
export const formatNseTime = fmtTime;

/** yyyy-MM-dd for a calendar day in IST (en-CA locale = ISO date order). */
export function istTodayYmd(at: Date = new Date()): string {
  return at.toLocaleDateString("en-CA", { timeZone: IST_ZONE });
}

/** Start of an IST calendar day as ISO instant (for API `from` / exclusive `to`). */
export function istDayStartIso(yyyyMmDd: string): string {
  return new Date(`${yyyyMmDd}T00:00:00+05:30`).toISOString();
}

/** Add calendar days to yyyy-MM-dd, interpreted in IST. */
export function istAddDaysYmd(yyyyMmDd: string, days: number): string {
  const d = new Date(`${yyyyMmDd}T12:00:00+05:30`);
  d.setDate(d.getDate() + days);
  return d.toLocaleDateString("en-CA", { timeZone: IST_ZONE });
}

/** Inclusive IST day span → API range [from, to) with `to` at start of day after end. */
export function istInclusiveDayRange(fromYmd: string, toYmd: string): { from: string; to: string } {
  const start = fromYmd <= toYmd ? fromYmd : toYmd;
  const end = fromYmd <= toYmd ? toYmd : fromYmd;
  return {
    from: istDayStartIso(start),
    to: istDayStartIso(istAddDaysYmd(end, 1)),
  };
}

/** Today's IST window for live signal monitor queries. */
export function istTodayApiRange(): { from: string; to: string } {
  const today = istTodayYmd();
  return {
    from: istDayStartIso(today),
    to: istDayStartIso(istAddDaysYmd(today, 1)),
  };
}
