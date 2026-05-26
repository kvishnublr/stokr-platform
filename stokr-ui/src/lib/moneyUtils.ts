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

export type PnlDataSource = "BROKER" | "WORKSTATION" | "POSITIONS" | "OMS" | "UNKNOWN";

export type ResolvedAccountPnl = AccountPnlSnapshot & { source: PnlDataSource };

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

/** Broker truth snapshot from workstation (`brokerTruth` field). */
export function extractBrokerTruthPnl(
  brokerTruth: Record<string, unknown> | undefined,
): AccountPnlSnapshot | null {
  if (!brokerTruth || brokerTruth.brokerConnected !== true || !brokerTruth.lastSyncAt) return null;
  const mtm = pickMoney(brokerTruth, "totalMtmPnl", "totalMtm");
  const unrealized = pickMoney(brokerTruth, "totalUnrealizedPnl");
  const realized = pickMoney(brokerTruth, "totalRealizedPnl");
  const openRaw = brokerTruth.openPositionCount;
  const openParsed = openRaw == null ? null : Number(openRaw);
  if (mtm == null && unrealized == null && realized == null) return null;
  return {
    mtm: mtm ?? (realized != null && unrealized != null ? realized + unrealized : null),
    unrealized,
    realized,
    openPositions: Number.isFinite(openParsed) ? openParsed : null,
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

/** True when every P&L leg is zero or unknown. */
function isZeroPnlSnapshot(snapshot: AccountPnlSnapshot): boolean {
  const mtm = snapshot.mtm ?? 0;
  const unrealized = snapshot.unrealized ?? 0;
  const realized = snapshot.realized ?? 0;
  return mtm === 0 && unrealized === 0 && realized === 0;
}

function hasPnlValues(snapshot: AccountPnlSnapshot): boolean {
  return snapshot.mtm != null || snapshot.unrealized != null || snapshot.realized != null;
}

/** Unified P&L resolution: broker truth when it has open legs or non-zero P&L, else workstation rows. */
export function resolveAccountPnl(input: {
  brokerTruth?: Record<string, unknown>;
  accountSummary?: Record<string, unknown>;
  openPositions?: Array<Record<string, unknown>>;
  portfolioOverview?: Record<string, unknown>;
}): ResolvedAccountPnl {
  const fromBroker = extractBrokerTruthPnl(input.brokerTruth);
  const fromRows = sumPositionsPnl(input.openPositions);
  const fromWs = extractAccountPnl(input.accountSummary, fromRows.openPositions);
  const fromOms = extractAccountPnl(input.portfolioOverview);

  const brokerHasOpen = (fromBroker?.openPositions ?? 0) > 0;
  const brokerHasPnl = fromBroker != null && !isZeroPnlSnapshot(fromBroker);
  if (fromBroker && (brokerHasOpen || brokerHasPnl)) {
    return { ...fromBroker, source: "BROKER" };
  }

  if ((fromRows.openPositions ?? 0) > 0 && fromRows.mtm != null && !isZeroPnlSnapshot(fromRows)) {
    return { ...fromRows, source: "POSITIONS" };
  }

  if (hasPnlValues(fromWs) && !isZeroPnlSnapshot(fromWs)) {
    return { ...fromWs, source: "WORKSTATION" };
  }

  if (fromRows.mtm != null) {
    return { ...fromRows, source: "POSITIONS" };
  }

  if (hasPnlValues(fromWs)) {
    return { ...fromWs, source: "WORKSTATION" };
  }

  if (fromBroker) {
    return { ...fromBroker, source: "BROKER" };
  }

  if (hasPnlValues(fromOms)) {
    return { ...fromOms, source: "OMS" };
  }

  return { mtm: null, unrealized: null, realized: null, openPositions: null, source: "UNKNOWN" };
}

export function pnlToneClass(value: number | null | undefined, isLight = true): string {
  if (value == null || !Number.isFinite(value)) {
    return isLight ? "text-neutral-900" : "text-neutral-100";
  }
  if (value > 0) return "text-emerald-600 dark:text-emerald-400";
  if (value < 0) return "text-rose-600 dark:text-rose-400";
  return isLight ? "text-neutral-700" : "text-neutral-300";
}

export function formatPnlDisplay(value: number | null | undefined, opts?: { compact?: boolean }): string {
  if (value == null) return "—";
  return formatInr(value, opts);
}

/** Market value for an open leg: |qty| × (LTP, else avg, else 0). */
export function computePositionNotional(
  qty: number | null | undefined,
  ltp: number | null | undefined,
  avgPrice: number | null | undefined,
): number {
  const q = Math.abs(qty ?? 0);
  if (q <= 0) return 0;
  const px = (ltp != null && ltp > 0 ? ltp : avgPrice) ?? 0;
  return q * px;
}

/** Row-level quantity source for positions tables (workstation-first). */
export function resolvePositionQuantitySource(
  row: Record<string, unknown> | undefined,
  brokerConnected: boolean,
): "BROKER" | "OMS" {
  const explicit = row?.pnlSource ?? row?.quantitySource;
  if (explicit != null && String(explicit).trim()) {
    return String(explicit).toUpperCase() === "BROKER" ? "BROKER" : "OMS";
  }
  if (brokerConnected) {
    const brokerQty = parseMoney(row?.brokerQty);
    if (brokerQty != null && brokerQty !== 0) return "BROKER";
  }
  return "OMS";
}

export type PositionParityBadge = {
  label: string;
  tone: "amber" | "sky" | "rose";
};

/** Human-readable parity badge from workstation row fields (avoid exposure API drift labels). */
export function resolvePositionParityBadge(
  input: {
    parityState: string | null;
    qty: number;
    brokerQty: number | null;
  },
  brokerConnected: boolean,
): PositionParityBadge | null {
  const state = input.parityState?.toUpperCase() ?? null;
  if (!state || state === "SYNCED" || state === "FLAT") return null;

  if (state === "PARTIAL_FILL") {
    return { label: "PARTIAL FILL", tone: "sky" };
  }
  if (state === "EXIT_PENDING") {
    return { label: "EXIT PENDING", tone: "sky" };
  }
  if (state === "MISMATCH") {
    if (brokerConnected && (input.brokerQty == null || input.brokerQty === 0) && input.qty !== 0) {
      return { label: "NOT AT BROKER", tone: "amber" };
    }
    if (input.brokerQty != null && input.brokerQty !== input.qty) {
      return { label: "QTY MISMATCH", tone: "rose" };
    }
    return { label: "SYNC DRIFT", tone: "amber" };
  }
  return { label: state.replace(/_/g, " "), tone: "amber" };
}
