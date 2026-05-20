/** All user-facing timestamps are displayed in IST (Asia/Kolkata = UTC+5:30). */
export const IST_ZONE = "Asia/Kolkata";
export const IST_LOCALE = "en-IN";

export function fmtTime(iso: string | Date | null | undefined): string {
  if (!iso) return "—";
  const d = typeof iso === "string" ? new Date(iso) : iso;
  return d.toLocaleTimeString(IST_LOCALE, { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZone: IST_ZONE });
}

export function fmtDate(iso: string | Date | null | undefined): string {
  if (!iso) return "—";
  const d = typeof iso === "string" ? new Date(iso) : iso;
  return d.toLocaleDateString(IST_LOCALE, { day: "2-digit", month: "short", timeZone: IST_ZONE });
}

export function fmtDateTime(iso: string | Date | null | undefined): string {
  if (!iso) return "—";
  return fmtTime(iso) + "  " + fmtDate(iso);
}

export function fmtDateLong(iso: string | Date | null | undefined): string {
  if (!iso) return "—";
  const d = typeof iso === "string" ? new Date(iso) : iso;
  return d.toLocaleDateString(IST_LOCALE, { weekday: "long", day: "numeric", month: "long", timeZone: IST_ZONE });
}

export function fmtDateWeekday(iso: string | Date | null | undefined): string {
  if (!iso) return "—";
  const d = typeof iso === "string" ? new Date(iso) : iso;
  return d.toLocaleDateString(IST_LOCALE, { weekday: "short", month: "short", day: "numeric", timeZone: IST_ZONE });
}

export function fmtDateRange(from: Date, to: Date): string {
  const opts: Intl.DateTimeFormatOptions = { month: "short", day: "numeric", year: "numeric", timeZone: IST_ZONE };
  return `${from.toLocaleDateString(IST_LOCALE, opts)} → ${to.toLocaleDateString(IST_LOCALE, opts)}`;
}
