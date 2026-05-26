import { parseMoney } from "./moneyUtils";

export type IntradaySignalRow = Record<string, unknown>;

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
  if (k.includes("GHOST")) return "Not at broker";
  if (k.includes("QTY")) return "Qty mismatch";
  if (k.includes("DRIFT")) return "Sync drift";
  return kind.replace(/_/g, " ").toLowerCase();
}
