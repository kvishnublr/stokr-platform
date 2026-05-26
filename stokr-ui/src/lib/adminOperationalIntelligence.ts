import { asArray, asRecord, type OpsSnapshot } from "../components/admin/cockpit/opsTypes";
import type { AdminRiskDashboardDto, StrategyRiskStateDto } from "../api/riskDashboard";
import type { GlobalCapitalSummary } from "../api/riskDashboard";
import type { ReconciliationEventDto } from "../api/reconciliation";

/** Minimal signal shape for client-side intelligence derivations. */
export type IntelSignal = {
  id: string;
  strategyName: string | null;
  symbol: string | null;
  signalType: string | null;
  pipeline: string | null;
  confidenceScore: number | null;
  entryReferencePrice: number | null;
  stopPrice: number | null;
  targetPrice: number | null;
  suggestedQty: number | null;
  reason: string | null;
  marketRegime: string | null;
  createdAt: string | null;
  outcomeStatus: string | null;
  realizedPnl: number | null;
  unrealizedPnl: number | null;
  maxFavorableExcursion: number | null;
  maxAdverseExcursion: number | null;
  hitTarget: boolean | null;
  hitStoploss: boolean | null;
  riskRewardAchieved: number | null;
  executionLatencyMs: number | null;
};

export type SignalConfidenceProfile = {
  probability: number;
  regimeConfidence: number;
  structureQuality: number;
  participationQuality: number;
  rrQuality: number;
  volatilityQuality: number;
  exhaustionRisk: number;
  trendAlignment: number;
  composite: number;
  radar: { axis: string; value: number }[];
};

export type FalseBreakoutFlag = {
  label: string;
  severity: "low" | "medium" | "high";
  detail: string;
};

export type SignalCluster = {
  kind: "symbol_storm" | "strategy_burst" | "sector_wave" | "correlated";
  label: string;
  intensity: number;
  count: number;
  keys: string[];
};

export type StrategyDnaProfile = {
  momentum: number;
  meanReversion: number;
  breakout: number;
  volatilityExpansion: number;
  scalpSwing: number;
  aggressiveness: number;
  riskProfile: number;
  archetype: string;
};

export type MarketFitAssessment = {
  score: number;
  regime: string;
  verdict: "favored" | "neutral" | "degraded";
  reasons: string[];
};

export type RejectionReason = {
  code: string;
  label: string;
  severity: number;
};

export type ExecutionStateStep = {
  id: string;
  label: string;
  status: "done" | "active" | "pending" | "failed";
};

export type OperationalInsight = {
  id: string;
  tone: "info" | "warn" | "critical";
  title: string;
  detail: string;
  action?: string;
};

function normConfidence(score: number | null | undefined): number {
  if (score == null || Number.isNaN(Number(score))) return 0;
  const n = Number(score);
  return n <= 1 ? Math.round(n * 100) : Math.round(Math.min(100, n));
}

function computeRR(entry: number | null, stop: number | null, target: number | null): number | null {
  if (entry == null || stop == null || target == null) return null;
  const risk = Math.abs(entry - stop);
  if (risk <= 0) return null;
  return Math.abs(target - entry) / risk;
}

function clamp(n: number, lo = 0, hi = 100): number {
  return Math.max(lo, Math.min(hi, Math.round(n)));
}

function sectorOf(symbol: string | null): string {
  const s = String(symbol ?? "").replace(/^NSE:/, "").toUpperCase();
  if (!s) return "UNKNOWN";
  if (s.includes("BANK") || s.includes("HDFC") || s.includes("ICICI") || s.includes("AXIS")) return "BANKING";
  if (s.includes("IT") || s.includes("INFY") || s.includes("TCS") || s.includes("TECH")) return "IT";
  if (s.includes("PHARMA") || s.includes("SUN") || s.includes("CIPLA")) return "PHARMA";
  if (s.includes("AUTO") || s.includes("MARUTI") || s.includes("TATA")) return "AUTO";
  return s.slice(0, 4);
}

export function buildSignalConfidenceProfile(signal: IntelSignal): SignalConfidenceProfile {
  const conf = normConfidence(signal.confidenceScore);
  const regime = String(signal.marketRegime ?? "").toUpperCase();
  const rr = signal.riskRewardAchieved ?? computeRR(signal.entryReferencePrice, signal.stopPrice, signal.targetPrice);
  const rrScore = rr != null ? clamp(rr >= 2 ? 85 : rr >= 1.5 ? 70 : rr >= 1 ? 55 : 35) : 45;

  const structureQuality = clamp(
    (conf * 0.4) +
      (signal.entryReferencePrice != null && signal.stopPrice != null && signal.targetPrice != null ? 25 : 0) +
      (signal.reason && signal.reason.length > 20 ? 15 : 5),
  );

  const participationQuality = clamp(
    conf * 0.6 + (signal.suggestedQty != null && signal.suggestedQty > 0 ? 20 : 0),
  );

  const regimeConfidence = regime.includes("TREND") || regime.includes("EXPANSION")
    ? 78
    : regime.includes("CHOP") || regime.includes("RANGE")
      ? 42
      : regime
        ? 62
        : 50;

  const mfe = signal.maxFavorableExcursion ?? 0;
  const mae = signal.maxAdverseExcursion ?? 0;
  const exhaustionRisk = clamp(
    mae > 0 && mfe > 0 && mae / (mfe + mae) > 0.65 ? 75 : mae > mfe * 1.2 ? 68 : 25,
  );

  const side = String(signal.signalType ?? "").toUpperCase();
  const trendAlignment =
    regime.includes("UP") && side === "BUY" ? 82 :
    regime.includes("DOWN") && side === "SELL" ? 82 :
    regime.includes("CHOP") ? 38 : 58;

  const volatilityQuality = clamp(
    regime.includes("EXPANSION") || regime.includes("VOL") ? 72 : regime.includes("CHOP") ? 40 : 58,
  );

  const probability = clamp(
    conf * 0.35 +
      regimeConfidence * 0.15 +
      structureQuality * 0.15 +
      rrScore * 0.15 +
      trendAlignment * 0.1 +
      participationQuality * 0.1 -
      exhaustionRisk * 0.1,
  );

  const composite = clamp(
    (probability + structureQuality + participationQuality + rrScore + regimeConfidence + trendAlignment) / 6 -
      exhaustionRisk * 0.08,
  );

  const radar = [
    { axis: "Prob", value: probability },
    { axis: "Regime", value: regimeConfidence },
    { axis: "Struct", value: structureQuality },
    { axis: "Part", value: participationQuality },
    { axis: "RR", value: rrScore },
    { axis: "Vol", value: volatilityQuality },
    { axis: "Trend", value: trendAlignment },
    { axis: "Exhaust", value: 100 - exhaustionRisk },
  ];

  return {
    probability,
    regimeConfidence,
    structureQuality,
    participationQuality,
    rrQuality: rrScore,
    volatilityQuality,
    exhaustionRisk,
    trendAlignment,
    composite,
    radar,
  };
}

export function detectFalseBreakoutFlags(signal: IntelSignal, profile: SignalConfidenceProfile): FalseBreakoutFlag[] {
  const flags: FalseBreakoutFlag[] = [];
  const regime = String(signal.marketRegime ?? "").toUpperCase();
  const rr = signal.riskRewardAchieved ?? computeRR(signal.entryReferencePrice, signal.stopPrice, signal.targetPrice);
  const outcome = String(signal.outcomeStatus ?? "").toUpperCase();

  if (profile.exhaustionRisk >= 65) {
    flags.push({ label: "High trap probability", severity: "high", detail: "Adverse excursion dominates favorable move" });
  } else if (profile.exhaustionRisk >= 45) {
    flags.push({ label: "Momentum weakening", severity: "medium", detail: "MFE/MAE ratio suggests fading impulse" });
  }

  if (regime.includes("CHOP") || regime.includes("RANGE")) {
    flags.push({ label: "Chop regime", severity: "medium", detail: "Breakout signals degrade in range-bound tape" });
  }

  if (rr != null && rr < 1.2) {
    flags.push({ label: "Poor RR", severity: rr < 1 ? "high" : "medium", detail: `Risk/reward ${rr.toFixed(1)} below institutional gate` });
  }

  if (profile.participationQuality < 45) {
    flags.push({ label: "Thin participation", severity: "medium", detail: "Low conviction or sizing on signal" });
  }

  if (outcome === "EXPIRED" || outcome === "MISSED") {
    flags.push({ label: "Late expansion", severity: "low", detail: "Signal expired before follow-through" });
  }

  if (outcome === "STOPLOSS_HIT" && profile.trendAlignment < 50) {
    flags.push({ label: "Weak continuation", severity: "high", detail: "Stop hit with poor trend alignment" });
  }

  const reason = String(signal.reason ?? "").toLowerCase();
  if (reason.includes("reject") || reason.includes("wick")) {
    flags.push({ label: "Rejection candle", severity: "medium", detail: "Rationale mentions rejection structure" });
  }

  return flags.slice(0, 5);
}

export function detectSignalClusters(signals: IntelSignal[]): SignalCluster[] {
  const bySymbol = new Map<string, number>();
  const byStrategy = new Map<string, number>();
  const bySector = new Map<string, number>();
  const strategySymbolPairs = new Map<string, number>();

  for (const s of signals) {
    const sym = String(s.symbol ?? "UNKNOWN").toUpperCase();
    const strat = String(s.strategyName ?? "UNKNOWN").toUpperCase();
    bySymbol.set(sym, (bySymbol.get(sym) ?? 0) + 1);
    byStrategy.set(strat, (byStrategy.get(strat) ?? 0) + 1);
    const sec = sectorOf(s.symbol);
    bySector.set(sec, (bySector.get(sec) ?? 0) + 1);
    const pair = `${strat}::${sym}`;
    strategySymbolPairs.set(pair, (strategySymbolPairs.get(pair) ?? 0) + 1);
  }

  const clusters: SignalCluster[] = [];

  for (const [sym, count] of bySymbol) {
    if (count >= 3) {
      clusters.push({
        kind: "symbol_storm",
        label: `${sym.replace(/^NSE:/, "")} overfiring`,
        intensity: Math.min(1, count / 8),
        count,
        keys: [sym],
      });
    }
  }

  for (const [strat, count] of byStrategy) {
    if (count >= 4) {
      clusters.push({
        kind: "strategy_burst",
        label: `${strat} burst`,
        intensity: Math.min(1, count / 12),
        count,
        keys: [strat],
      });
    }
  }

  for (const [sec, count] of bySector) {
    if (count >= 5 && sec !== "UNKNOWN") {
      clusters.push({
        kind: "sector_wave",
        label: `${sec} sector cluster`,
        intensity: Math.min(1, count / 15),
        count,
        keys: [sec],
      });
    }
  }

  const dupPairs = [...strategySymbolPairs.entries()].filter(([, c]) => c >= 2);
  if (dupPairs.length >= 2) {
    clusters.push({
      kind: "correlated",
      label: "Correlated signal pairs",
      intensity: Math.min(1, dupPairs.length / 6),
      count: dupPairs.reduce((a, [, c]) => a + c, 0),
      keys: dupPairs.slice(0, 4).map(([k]) => k),
    });
  }

  return clusters.sort((a, b) => b.intensity - a.intensity).slice(0, 8);
}

export function inferStrategyDna(code: string, riskLevel: string): StrategyDnaProfile {
  const c = code.toUpperCase();
  let momentum = 40;
  let meanReversion = 35;
  let breakout = 45;
  let volatilityExpansion = 40;
  let scalpSwing = 50;
  let aggressiveness = riskLevel === "HIGH" ? 75 : riskLevel === "LOW" ? 35 : 55;
  let riskProfile = riskLevel === "HIGH" ? 80 : riskLevel === "LOW" ? 30 : 55;

  if (c.includes("VWAP") || c.includes("MEAN") || c.includes("REVERT")) {
    meanReversion = 85; momentum = 30; breakout = 25; scalpSwing = 60;
  } else if (c.includes("BREAK") || c.includes("ORB") || c.includes("OPEN")) {
    breakout = 88; momentum = 70; meanReversion = 20; scalpSwing = 45;
  } else if (c.includes("SPIKE") || c.includes("VOL") || c.includes("EXPAND")) {
    volatilityExpansion = 90; momentum = 65; breakout = 55; scalpSwing = 35;
  } else if (c.includes("SCALP") || c.includes("MICRO")) {
    scalpSwing = 85; aggressiveness = 70; momentum = 55;
  } else if (c.includes("SWING") || c.includes("TREND")) {
    scalpSwing = 25; momentum = 80; breakout = 60;
  }

  const max = Math.max(momentum, meanReversion, breakout, volatilityExpansion);
  const archetype =
    max === momentum ? "Momentum" :
    max === meanReversion ? "Mean reversion" :
    max === breakout ? "Breakout" :
    max === volatilityExpansion ? "Vol expansion" : "Hybrid";

  return { momentum, meanReversion, breakout, volatilityExpansion, scalpSwing, aggressiveness, riskProfile, archetype };
}

export function assessMarketFit(
  dna: StrategyDnaProfile,
  regime: string,
  signalsToday: number,
  scanFailures: number,
): MarketFitAssessment {
  const r = regime.toUpperCase() || "UNKNOWN";
  const reasons: string[] = [];
  let score = 55;

  if (r.includes("CHOP") || r.includes("RANGE")) {
    if (dna.meanReversion >= 70) { score += 18; reasons.push("Mean-reversion favored in chop"); }
    else if (dna.breakout >= 70) { score -= 22; reasons.push("Breakout degraded in chop"); }
  }
  if (r.includes("EXPANSION") || r.includes("VOL")) {
    if (dna.volatilityExpansion >= 70) { score += 20; reasons.push("Vol expansion strategy aligned"); }
  }
  if (r.includes("TREND") || r.includes("UP") || r.includes("DOWN")) {
    if (dna.momentum >= 65) { score += 15; reasons.push("Momentum aligned with trend regime"); }
  }
  if (signalsToday === 0 && scanFailures > 2) {
    score -= 15;
    reasons.push("Scanner stress — check velocity gates");
  }
  if (signalsToday > 8 && dna.scalpSwing < 40) {
    score -= 10;
    reasons.push("High signal noise for swing profile");
  }

  score = clamp(score);
  const verdict: MarketFitAssessment["verdict"] = score >= 68 ? "favored" : score <= 42 ? "degraded" : "neutral";
  if (reasons.length === 0) reasons.push(verdict === "favored" ? "Regime compatible with DNA" : "Awaiting clearer regime fit");

  return { score, regime: r || "UNKNOWN", verdict, reasons };
}

export type StrategyEngineIntelInput = {
  code: string;
  enabled: boolean;
  visibleToUsers: boolean;
  riskLevel: string;
  signalsToday?: number;
  runningInstances?: number;
  scanFailures?: number;
  haltedReason?: string | null;
  boundGroups?: number;
  lastSignalAt?: string | null;
  marketRegime?: string;
};

export function buildRejectionWaterfall(s: StrategyEngineIntelInput): RejectionReason[] {
  const reasons: RejectionReason[] = [];
  if (!s.enabled) reasons.push({ code: "disabled", label: "Strategy disabled in catalog", severity: 100 });
  if (!s.visibleToUsers) reasons.push({ code: "hidden", label: "Hidden from traders", severity: 70 });
  if ((s.boundGroups ?? 0) === 0) reasons.push({ code: "no_binding", label: "No universe binding — scanner idle", severity: 95 });
  if ((s.runningInstances ?? 0) === 0) reasons.push({ code: "no_runtime", label: "No RUNNING runtime instances", severity: 90 });
  if (s.haltedReason) reasons.push({ code: "halted", label: s.haltedReason, severity: 88 });
  if ((s.scanFailures ?? 0) > 3) reasons.push({ code: "scan_fail", label: `Scan failures elevated (${s.scanFailures})`, severity: 75 });
  if ((s.signalsToday ?? 0) === 0) {
    const last = s.lastSignalAt ? Date.now() - new Date(s.lastSignalAt).getTime() : null;
    if (last != null && last < 5 * 60_000) reasons.push({ code: "cooldown", label: "Cooldown after recent signal", severity: 40 });
    else reasons.push({ code: "low_confidence", label: "Low confidence / no continuation", severity: 55 });
    reasons.push({ code: "regime", label: "Invalid or weak market regime fit", severity: 50 });
    reasons.push({ code: "poor_rr", label: "Poor RR at scanner gate", severity: 45 });
  }
  return reasons.sort((a, b) => b.severity - a.severity).slice(0, 6);
}

export function computeStrategyQualityScore(
  signalsToday: number,
  scanFailures: number,
  fit: MarketFitAssessment,
  strategyStats?: { targetHit?: number; slHit?: number },
): number {
  const wins = strategyStats?.targetHit ?? 0;
  const losses = strategyStats?.slHit ?? 0;
  const winRate = wins + losses > 0 ? wins / (wins + losses) : 0.5;
  return clamp(
    fit.score * 0.35 +
      winRate * 100 * 0.25 +
      (signalsToday > 0 ? 15 : 0) +
      (scanFailures === 0 ? 15 : Math.max(0, 15 - scanFailures * 3)) +
      (fit.verdict === "favored" ? 10 : fit.verdict === "degraded" ? -10 : 0),
  );
}

export function buildStrategyCorrelationPairs(
  engines: { code: string; signalsToday?: number }[],
): { a: string; b: string; overlap: number }[] {
  const active = engines.filter((e) => (e.signalsToday ?? 0) > 0);
  const pairs: { a: string; b: string; overlap: number }[] = [];
  for (let i = 0; i < active.length; i++) {
    for (let j = i + 1; j < active.length; j++) {
      const a = active[i];
      const b = active[j];
      const overlap = Math.min(a.signalsToday ?? 0, b.signalsToday ?? 0) / Math.max(a.signalsToday ?? 1, b.signalsToday ?? 1, 1);
      if (overlap >= 0.4) pairs.push({ a: a.code, b: b.code, overlap: Math.round(overlap * 100) });
    }
  }
  return pairs.sort((x, y) => y.overlap - x.overlap).slice(0, 8);
}

export function extractOmsLatencyMs(snapshot: OpsSnapshot | undefined): number | null {
  const oms = asRecord(snapshot?.oms);
  const v = oms?.executionAvgLatencyMs ?? oms?.avgLatencyMs ?? oms?.latencyMs;
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

export function extractMarketRegime(snapshot: OpsSnapshot | undefined): string {
  const fresh = asRecord(snapshot?.marketFreshness);
  return String(fresh?.regime ?? fresh?.status ?? snapshot?.marketInfra?.sessionState ?? "UNKNOWN");
}

export function buildExposureMap(
  risk: AdminRiskDashboardDto,
  capital?: GlobalCapitalSummary,
): {
  byStrategy: { key: string; positions: number; max: number; pnl: number | null; utilization: number }[];
  totalPositions: number;
  liveStrategies: number;
  directionalBias: "long" | "short" | "neutral";
} {
  const rows = risk.strategyRiskStates.map((r) => ({
    key: r.strategyKey,
    positions: r.openPositions,
    max: r.maxPositions,
    pnl: r.todayPnl,
    utilization: r.maxPositions > 0 ? r.openPositions / r.maxPositions : 0,
  }));
  const totalPositions = rows.reduce((a, r) => a + r.positions, 0);
  const pnlSum = rows.reduce((a, r) => a + (r.pnl ?? 0), 0);
  return {
    byStrategy: rows.sort((a, b) => b.utilization - a.utilization),
    totalPositions,
    liveStrategies: risk.liveEnabledStrategies,
    directionalBias: pnlSum > 100 ? "long" : pnlSum < -100 ? "short" : "neutral",
  };
}

export function buildDrawdownEngine(risk: AdminRiskDashboardDto): {
  sessionPnl: number;
  worstStrategies: StrategyRiskStateDto[];
  worstSymbols: { symbol: string; pnl: number }[];
  rollingStress: number;
} {
  const sessionPnl = risk.strategyRiskStates.reduce((a, r) => a + (r.todayPnl ?? 0), 0);
  const worstStrategies = [...risk.strategyRiskStates]
    .filter((r) => r.todayPnl != null)
    .sort((a, b) => (a.todayPnl ?? 0) - (b.todayPnl ?? 0))
    .slice(0, 5);
  const nearLimit = risk.strategyRiskStates.filter(
    (r) => r.dailyLossLimit != null && r.todayPnl != null && r.todayPnl < -r.dailyLossLimit * 0.7,
  ).length;
  return {
    sessionPnl,
    worstStrategies,
    worstSymbols: [],
    rollingStress: clamp(nearLimit * 25 + (sessionPnl < 0 ? Math.min(50, Math.abs(sessionPnl) / 100) : 0)),
  };
}

export function buildBrokerTruthScore(
  snapshot: OpsSnapshot | undefined,
  reconEvents: ReconciliationEventDto[],
): { score: number; websocketHealth: number; fillSync: number; positionFreshness: number; omsConsistency: number; flags: string[] } {
  const broker = asRecord(snapshot?.brokerSessions);
  const oms = asRecord(snapshot?.oms);
  const fresh = asRecord(snapshot?.marketFreshness);
  const flags: string[] = [];

  const aggStatus = String(broker?.aggregateStatus ?? "").toUpperCase();
  const websocketHealth = aggStatus === "CONNECTED" || aggStatus === "OK" ? 92 : aggStatus.includes("RECONNECT") ? 55 : 35;
  if (websocketHealth < 60) flags.push("Websocket degraded");

  const openRecon = reconEvents.filter((e) => e.status === "OPEN").length;
  const fillSync = clamp(95 - openRecon * 12);
  if (openRecon > 0) flags.push(`${openRecon} open reconciliation item(s)`);

  const stale = fresh?.status === "STALE";
  const positionFreshness = stale ? 45 : 88;
  if (stale) flags.push("Market freshness stale — position marks lag");

  const omsState = String(oms?.omsPlaneState ?? "").toUpperCase();
  const omsConsistency = omsState === "OPERATIONAL" ? 90 : omsState === "DEGRADED" ? 52 : 70;
  if (omsState === "DEGRADED") flags.push(String(oms?.degradationReason ?? "OMS plane degraded"));

  const score = clamp((websocketHealth + fillSync + positionFreshness + omsConsistency) / 4);
  return { score, websocketHealth, fillSync, positionFreshness, omsConsistency, flags };
}

export function mapOrderToExecutionSteps(state: string | null): ExecutionStateStep[] {
  const s = String(state ?? "").toUpperCase();
  const order = [
    { id: "generated", label: "Generated", match: ["CREATED"] },
    { id: "approved", label: "Approved", match: ["VALIDATED"] },
    { id: "risk", label: "Risk checked", match: ["RISK_PASSED", "VALIDATED"] },
    { id: "sent", label: "Sent", match: ["SUBMITTED"] },
    { id: "broker", label: "Broker accepted", match: ["ACCEPTED"] },
    { id: "exchange", label: "Exchange accepted", match: ["ACCEPTED", "PARTIALLY_FILLED"] },
    { id: "filled", label: "Filled", match: ["FILLED", "EXIT_FILLED", "PARTIALLY_FILLED"] },
    { id: "reconciled", label: "Reconciled", match: ["FILLED", "EXIT_FILLED"] },
  ];

  const failed = s === "REJECTED" || s === "FAILED" || s === "CANCELLED";
  const rank = (matches: string[]) => matches.some((m) => s === m || s.includes(m));

  let passed = true;
  return order.map((step) => {
    if (failed && !rank(step.match)) {
      return { id: step.id, label: step.label, status: passed ? "done" : "failed" } as ExecutionStateStep;
    }
    if (rank(step.match)) {
      passed = true;
      const isLast = step.id === "reconciled" || (step.id === "filled" && s.includes("FILL"));
      return { id: step.id, label: step.label, status: isLast && s.includes("FILL") ? "done" : "active" } as ExecutionStateStep;
    }
    if (passed) return { id: step.id, label: step.label, status: "pending" };
    return { id: step.id, label: step.label, status: "done" };
  });
}

export function buildOperationalInsights(
  snapshot: OpsSnapshot | undefined,
  risk: AdminRiskDashboardDto | undefined,
  signals: IntelSignal[],
): OperationalInsight[] {
  const insights: OperationalInsight[] = [];
  const oms = asRecord(snapshot?.oms);
  const stuck = Number(oms?.stuckOrdersApprox ?? 0);
  if (stuck > 0) {
    insights.push({ id: "stuck", tone: "warn", title: "Stuck orders detected", detail: `${stuck} orders past SLA`, action: "/admin/oms" });
  }
  if (risk?.killSwitchActive) {
    insights.push({ id: "kill", tone: "critical", title: "Kill switch active", detail: "Global halt engaged", action: "/admin/ops" });
  }
  if (risk && risk.openReconciliationAlerts > 0) {
    insights.push({ id: "recon", tone: "critical", title: "Broker divergence", detail: `${risk.openReconciliationAlerts} open recon alerts`, action: "/admin/risk-dashboard" });
  }
  const clusters = detectSignalClusters(signals);
  if (clusters.some((c) => c.intensity > 0.6)) {
    insights.push({ id: "storm", tone: "warn", title: "Signal storm", detail: clusters[0]?.label ?? "Cluster detected", action: "/admin/signals" });
  }
  const lat = extractOmsLatencyMs(snapshot);
  if (lat != null && lat > 800) {
    insights.push({ id: "lat", tone: "warn", title: "Execution latency elevated", detail: `${Math.round(lat)}ms OMS average`, action: "/admin/oms" });
  }
  return insights.slice(0, 6);
}

export function buildTopologyLoad(snapshot: OpsSnapshot | undefined): {
  queueCongestion: number;
  websocketTraffic: string;
  throughput: string;
  strategyLoad: number;
  schedulerLoad: number;
  latencyPropagation: number;
  brokerDegradation: boolean;
  replayActivity: number;
  riskPressure: number;
} {
  const oms = asRecord(snapshot?.oms);
  const scan = asRecord(snapshot?.scannerTelemetry);
  const replay = asRecord(snapshot?.replayInfra);
  const sys = asRecord(snapshot?.system);

  const stuck = Number(oms?.stuckOrdersApprox ?? 0);
  const ordersPerSec = Number(oms?.ordersPerSecApprox ?? 0);
  const running = Number(scan?.runningStrategyInstances ?? 0);
  const catalog = Number(scan?.catalogStrategies ?? 1);
  const activeReplay = Number(replay?.activeReplayJobs ?? 0);

  return {
    queueCongestion: clamp(stuck * 15 + (ordersPerSec > 2 ? 20 : 0)),
    websocketTraffic: String(asRecord(snapshot?.brokerSessions)?.aggregateStatus ?? "—"),
    throughput: ordersPerSec > 0 ? `${ordersPerSec.toFixed(1)} ord/s` : `${Number(oms?.ordersCreatedLast60s ?? 0)}/min`,
    strategyLoad: clamp((running / Math.max(catalog, 1)) * 100),
    schedulerLoad: clamp(Number(sys?.schedulerLoadPct ?? running * 12)),
    latencyPropagation: extractOmsLatencyMs(snapshot) ?? 0,
    brokerDegradation: Boolean(snapshot?.marketInfra?.globalBrokerHalt) || asRecord(snapshot?.brokerSessions)?.aggregateStatus === "DISCONNECTED",
    replayActivity: activeReplay,
    riskPressure: clamp(stuck * 10 + (Number(oms?.ordersRejected ?? 0) > 5 ? 25 : 0)),
  };
}
