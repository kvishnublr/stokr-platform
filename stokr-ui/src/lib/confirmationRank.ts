import { parseMoney } from "./moneyUtils";
import { computeRiskReward, type IntradaySignalRow } from "./intradaySignals";

export type ConfirmationTier = "A_PLUS" | "A" | "WATCH" | "SKIP";

export type ConfirmationBreakdown = {
  score: number;
  tier: ConfirmationTier;
  highConviction: boolean;
  confidencePct: number | null;
  riskReward: number | null;
  advAiScore: number | null;
};

export const CONFIDENCE_MIN_PCT = 70;
export const RR_MIN = 1.5;
export const SCORE_A_PLUS = 80;
export const SCORE_A = 68;
export const SCORE_WATCH = 50;

/** Normalize confidence to 0–100 (API may send 0–1 or 0–100). */
export function normalizeConfidencePct(value: unknown): number | null {
  const n = parseMoney(value);
  if (n == null || !Number.isFinite(n)) return null;
  if (n > 0 && n <= 1) return Math.round(n * 100);
  return Math.round(Math.min(100, Math.max(0, n)));
}

export function tierFromScore(score: number): ConfirmationTier {
  if (score >= SCORE_A_PLUS) return "A_PLUS";
  if (score >= SCORE_A) return "A";
  if (score >= SCORE_WATCH) return "WATCH";
  return "SKIP";
}

export function tierLabel(tier: ConfirmationTier): string {
  switch (tier) {
    case "A_PLUS":
      return "A+";
    case "A":
      return "A";
    case "WATCH":
      return "Watch";
    default:
      return "Skip";
  }
}

function riskRewardFromRow(row: IntradaySignalRow): number | null {
  const stored = parseMoney(row.riskReward);
  if (stored != null && stored > 0) return stored;
  return computeRiskReward(
    parseMoney(row.entryReferencePrice ?? row.entry),
    parseMoney(row.stopPrice),
    parseMoney(row.targetPrice),
  );
}

/**
 * Setup confirmation score (0–100). Separate from execution quality / ADV market-watch scores.
 * Optional advAiScore (0–100) adds alignment when ADV scanner tracks the same symbol.
 */
export function computeConfirmationRank(
  row: IntradaySignalRow,
  advAiScore?: number | null,
): ConfirmationBreakdown {
  const confidencePct = normalizeConfidencePct(row.confidenceScore ?? row.confidence);
  const riskReward = riskRewardFromRow(row);
  const adv =
    advAiScore != null && Number.isFinite(advAiScore) ? Math.round(Math.min(100, Math.max(0, advAiScore))) : null;

  let score = 42;

  if (confidencePct != null) {
    score = Math.round(confidencePct * 0.45);
  }

  if (riskReward != null && riskReward > 0) {
    const rrPts = Math.min(35, Math.round((Math.min(riskReward, 3) / 3) * 35));
    score += rrPts;
  } else {
    score -= 8;
  }

  if (adv != null) {
    score += Math.round(adv * 0.2);
  }

  if (riskReward != null && riskReward < RR_MIN) {
    score = Math.min(score, SCORE_A - 1);
  }

  if (confidencePct != null && confidencePct < 50) {
    score = Math.min(score, SCORE_WATCH + 5);
  }

  score = Math.round(Math.min(100, Math.max(0, score)));
  let tier = tierFromScore(score);

  if (riskReward != null && riskReward < RR_MIN && (tier === "A_PLUS" || tier === "A")) {
    tier = "WATCH";
  }

  const highConviction =
    tier === "A_PLUS" &&
    (confidencePct == null || confidencePct >= CONFIDENCE_MIN_PCT) &&
    riskReward != null &&
    riskReward >= RR_MIN;

  return {
    score,
    tier,
    highConviction,
    confidencePct,
    riskReward,
    advAiScore: adv,
  };
}

export function useBackendConfirmation(row: IntradaySignalRow): ConfirmationBreakdown | null {
  const score = parseMoney(row.confirmationScore);
  if (score == null) return null;
  const tierRaw = String(row.confirmationTier ?? "").toUpperCase();
  const tier: ConfirmationTier =
    tierRaw === "A_PLUS" || tierRaw === "A+" ? "A_PLUS" :
    tierRaw === "A" ? "A" :
    tierRaw === "WATCH" ? "WATCH" :
    tierRaw === "SKIP" ? "SKIP" :
    tierFromScore(score);
  return {
    score: Math.round(score),
    tier,
    highConviction: row.highConviction === true || row.highConviction === "true",
    confidencePct: normalizeConfidencePct(row.confidenceScore ?? row.confidence),
    riskReward: riskRewardFromRow(row),
    advAiScore: parseMoney(row.advAiScore),
  };
}

export function resolveConfirmation(
  row: IntradaySignalRow,
  advAiScore?: number | null,
): ConfirmationBreakdown {
  return useBackendConfirmation(row) ?? computeConfirmationRank(row, advAiScore);
}

export type SignalSortMode = "confirmation" | "time";

export function compareSignalsByConfirmation(
  a: IntradaySignalRow,
  b: IntradaySignalRow,
  advMap?: Map<string, number>,
): number {
  const ra = resolveConfirmation(a, lookupAdvScore(a, advMap));
  const rb = resolveConfirmation(b, lookupAdvScore(b, advMap));
  if (rb.score !== ra.score) return rb.score - ra.score;
  const ta = Date.parse(String(a.createdAt ?? ""));
  const tb = Date.parse(String(b.createdAt ?? ""));
  if (Number.isFinite(tb) && Number.isFinite(ta)) return tb - ta;
  return 0;
}

export function lookupAdvScore(row: IntradaySignalRow, advMap?: Map<string, number>): number | undefined {
  if (!advMap?.size) return undefined;
  const sym = String(row.symbol ?? "").trim().toUpperCase();
  if (!sym) return undefined;
  const bare = sym.includes(":") ? sym.slice(sym.indexOf(":") + 1) : sym;
  return advMap.get(sym) ?? advMap.get(bare) ?? advMap.get(`NSE:${bare}`);
}

export function sortSignals<T extends IntradaySignalRow>(
  rows: T[],
  mode: SignalSortMode,
  advMap?: Map<string, number>,
): T[] {
  const copy = [...rows];
  if (mode === "time") {
    copy.sort((a, b) => {
      const ta = Date.parse(String(a.createdAt ?? ""));
      const tb = Date.parse(String(b.createdAt ?? ""));
      if (Number.isFinite(tb) && Number.isFinite(ta)) return tb - ta;
      return 0;
    });
    return copy;
  }
  copy.sort((a, b) => compareSignalsByConfirmation(a, b, advMap));
  return copy;
}

export function pickTopConfirmation<T extends IntradaySignalRow>(
  rows: T[],
  advMap?: Map<string, number>,
  minTier: ConfirmationTier = "A",
): { row: T; rank: ConfirmationBreakdown } | null {
  const sorted = sortSignals(rows, "confirmation", advMap);
  const first = sorted[0];
  if (!first) return null;
  const rank = resolveConfirmation(first, lookupAdvScore(first, advMap));
  const minScore = minTier === "A_PLUS" ? SCORE_A_PLUS : minTier === "A" ? SCORE_A : SCORE_WATCH;
  if (rank.score < minScore) return null;
  return { row: first, rank };
}

export function passesHighConvictionFilter(row: IntradaySignalRow, advMap?: Map<string, number>): boolean {
  return resolveConfirmation(row, lookupAdvScore(row, advMap)).highConviction;
}

/** Build symbol → aiScore from ADV scanner rows (pipeline truth only). */
export function buildAdvAiScoreMap(
  scannerRows: Array<{ symbol?: string; aiScore?: number }> | undefined,
): Map<string, number> {
  const map = new Map<string, number>();
  for (const row of scannerRows ?? []) {
    const sym = String(row.symbol ?? "").trim().toUpperCase();
    const score = Number(row.aiScore);
    if (!sym || !Number.isFinite(score)) continue;
    const bare = sym.includes(":") ? sym.slice(sym.indexOf(":") + 1) : sym;
    map.set(sym, score);
    map.set(bare, score);
    map.set(`NSE:${bare}`, score);
  }
  return map;
}
