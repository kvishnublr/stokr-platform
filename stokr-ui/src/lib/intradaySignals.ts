import { parseMoney } from "./moneyUtils";
import { istTodayYmd, IST_ZONE } from "./dateUtils";

export type IntradaySignalRow = Record<string, unknown>;

/** True when timestamp falls on today's IST calendar day. */
export function isTodayIst(iso: string | null | undefined): boolean {
  if (!iso) return false;
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return false;
  return d.toLocaleDateString("en-CA", { timeZone: IST_ZONE }) === istTodayYmd();
}

export function bareSymbol(symbol: unknown): string {
  const raw = String(symbol ?? "").trim();
  if (!raw) return "—";
  const idx = raw.indexOf(":");
  return idx >= 0 ? raw.slice(idx + 1) : raw;
}

export function signalDirection(row: IntradaySignalRow): string {
  const value = String(row.signalType ?? row.side ?? "").trim().toUpperCase();
  if (!value || value === "HOLD") return "—";
  return value;
}

export function signalStrategyKey(row: IntradaySignalRow): string {
  return String(row.strategyKey ?? row.strategyName ?? row.strategy ?? "—");
}

export function formatConfidencePct(value: unknown): string {
  const n = parseMoney(value);
  if (n == null) return "—";
  const pct = n > 0 && n <= 1 ? n * 100 : n;
  return `${Math.round(pct)}%`;
}

export function computeRiskReward(
  entry: number | null,
  stop: number | null,
  target: number | null,
): number | null {
  if (entry == null || stop == null || target == null) return null;
  const risk = Math.abs(entry - stop);
  const reward = Math.abs(target - entry);
  if (risk <= 0) return null;
  return reward / risk;
}

export function normalizeSignalRow(row: IntradaySignalRow): IntradaySignalRow {
  const entry = parseMoney(row.entryReferencePrice ?? row.entry);
  const stop = parseMoney(row.stopPrice);
  const target = parseMoney(row.targetPrice);
  const riskReward = parseMoney(row.riskReward) ?? computeRiskReward(entry, stop, target);
  return {
    ...row,
    entryReferencePrice: entry,
    stopPrice: stop,
    targetPrice: target,
    riskReward,
  };
}

export function mismatchLabel(kind: string): string {
  const k = kind.toUpperCase();
  if (k.includes("GHOST")) return "Ghost — not at broker";
  if (k.includes("QTY")) return "Quantity mismatch";
  if (k.includes("DRIFT") || k.includes("CONFLICT")) return "Sync drift";
  if (k.includes("ORPHAN")) return "Orphan at broker";
  return kind.replace(/_/g, " ").toLowerCase();
}

export function mismatchHint(kind: string): string {
  const k = kind.toUpperCase();
  if (k.includes("GHOST")) return "Platform shows an open position the broker does not hold.";
  if (k.includes("QTY")) return "Broker quantity differs from the platform ledger.";
  if (k.includes("ORPHAN")) return "Broker holds a position not tracked on the platform.";
  return "Reconcile before increasing live size.";
}
