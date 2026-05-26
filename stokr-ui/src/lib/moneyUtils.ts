/** Parse broker/OMS money fields (number, string, BigDecimal JSON, null). */
export function parseMoney(v: unknown): number | null {
  if (v == null || v === "") return null;
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "bigint") return Number(v);
  if (typeof v === "object") {
    const o = v as Record<string, unknown>;
    if ("value" in o) return parseMoney(o.value);
    if ("amount" in o) return parseMoney(o.amount);
  }
  const s = String(v).trim();
  if (!s || s === "—" || s === "-") return null;
  const n = Number(s.replace(/[^0-9.eE+-]/g, ""));
  return Number.isFinite(n) ? n : null;
}

/** INR display; pass `null` only when the value is genuinely unknown (e.g. both APIs failed). */
export function formatInr(v: number | null, opts?: { compact?: boolean }): string {
  if (v == null) return "—";
  const abs = Math.abs(v);
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    notation: opts?.compact && abs >= 1e7 ? "compact" : "standard",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(v);
}

export type AccountPnlSnapshot = {
  mtm: number | null;
  unrealized: number | null;
  realized: number | null;
  openPositions: number | null;
};

function pickMoney(obj: Record<string, unknown> | undefined, ...keys: string[]): number | null {
  if (!obj) return null;
  for (const key of keys) {
    if (key in obj) {
      const n = parseMoney(obj[key]);
      if (n != null) return n;
    }
  }
  return null;
}

/** Sum per-position P&L rows (workstation openPositions). */
export function sumPositionsPnl(
  rows: Array<Record<string, unknown>> | undefined,
): AccountPnlSnapshot {
  if (!rows?.length) {
    return { mtm: null, unrealized: null, realized: null, openPositions: null };
  }
  let realized = 0;
  let unrealized = 0;
  for (const row of rows) {
    realized += parseMoney(row.realizedPnl) ?? 0;
    unrealized += parseMoney(row.unrealizedPnl) ?? 0;
  }
  return {
    mtm: realized + unrealized,
    unrealized,
    realized,
    openPositions: rows.length,
  };
}

/** Normalize workstation or portfolio overview payloads. */
export function extractAccountPnl(
  summary: Record<string, unknown> | undefined,
  openPositionsFallback?: number | null,
): AccountPnlSnapshot {
  const openRaw = summary?.openPositions ?? summary?.openPositionCount ?? summary?.open_positions;
  const openParsed = openRaw == null ? openPositionsFallback ?? null : Number(openRaw);

  return {
    mtm: pickMoney(summary, "totalPnl", "mtmPnl", "cumulativePnl", "total_pnl", "mtm_pnl"),
    unrealized: pickMoney(summary, "unrealizedPnl", "unrealized_pnl"),
    realized: pickMoney(summary, "realizedPnl", "realized_pnl"),
    openPositions: Number.isFinite(openParsed) ? openParsed : openPositionsFallback ?? null,
  };
}
