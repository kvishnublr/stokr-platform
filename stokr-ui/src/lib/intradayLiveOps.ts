import { bareSymbol, normalizeSignalRow, signalStrategyKey, type IntradaySignalRow } from "./intradaySignals";
import { isTodayIst } from "./intradaySignals";
import { formatPnlDisplay, parseMoney } from "./moneyUtils";

export type LiveOpsMetricId =
  | "livePnl"
  | "tradesHit"
  | "running"
  | "sl"
  | "notExecuted"
  | "openBook"
  | "guards";

export type LiveOpsMetric = {
  id: LiveOpsMetricId;
  label: string;
  value: string;
  sublabel?: string;
  tone: "neutral" | "positive" | "negative" | "warn" | "info";
  count: number;
  details: LiveOpsDetailRow[];
};

export type LiveOpsDetailRow = {
  key: string;
  primary: string;
  secondary?: string;
  meta?: string;
  tone?: "positive" | "negative" | "warn" | "neutral";
};

const CLOSED_OUTCOMES = new Set(["TARGET_HIT", "SL_HIT", "STOPLOSS_HIT", "PRESSURE_EXIT", "CLOSED", "EXPIRED", "TIME_EXIT"]);
const SL_OUTCOMES = new Set(["SL_HIT", "STOPLOSS_HIT"]);
const HIT_OUTCOMES = new Set(["TARGET_HIT"]);
const RUNNING_OUTCOMES = new Set(["RUNNING", "PENDING"]);
const SKIPPED_EXEC = new Set([
  "BLOCKED",
  "REJECTED",
  "OMS_REJECTED",
  "QUALITY_REJECTED",
  "NOT_EXECUTED",
  "INTELLIGENCE_ONLY",
  "COOLDOWN",
]);

function outcomeOf(row: IntradaySignalRow): string {
  return String(row.outcomeStatus ?? row.status ?? "").trim().toUpperCase();
}

function executionOf(row: IntradaySignalRow): string {
  return String(row.executionStatus ?? row.executionMode ?? row.pipeline ?? "").trim().toUpperCase();
}

function todaySignals(raw: Array<Record<string, unknown>> | undefined): IntradaySignalRow[] {
  return (raw ?? [])
    .map(normalizeSignalRow)
    .filter((s) => isTodayIst(String(s.createdAt ?? "")));
}

export function buildLiveOpsMetrics(input: {
  latestSignals?: Array<Record<string, unknown>>;
  openPositions?: Array<Record<string, unknown>>;
  livePnl?: unknown;
  guardEvents?: Array<Record<string, unknown>>;
  runningStrategies?: number;
  rejectedOrders?: number;
}): LiveOpsMetric[] {
  const signals = todaySignals(input.latestSignals);
  const openPositions = input.openPositions ?? [];
  const guards = input.guardEvents ?? [];

  const hits = signals.filter((s) => HIT_OUTCOMES.has(outcomeOf(s)));
  const slHits = signals.filter((s) => SL_OUTCOMES.has(outcomeOf(s)));
  const runningSignals = signals.filter((s) => RUNNING_OUTCOMES.has(outcomeOf(s)) && !CLOSED_OUTCOMES.has(outcomeOf(s)));
  const notExecuted = signals.filter((s) => {
    const o = outcomeOf(s);
    const ex = executionOf(s);
    if (CLOSED_OUTCOMES.has(o) && HIT_OUTCOMES.has(o)) return false;
    if (RUNNING_OUTCOMES.has(o)) return false;
    return SKIPPED_EXEC.has(ex) || SKIPPED_EXEC.has(o) || ex.includes("BLOCK") || ex.includes("REJECT");
  });

  const livePnl = parseMoney(input.livePnl);
  const pnlTone: LiveOpsMetric["tone"] =
    livePnl == null || livePnl === 0 ? "neutral" : livePnl > 0 ? "positive" : "negative";

  const openDetails: LiveOpsDetailRow[] = openPositions.slice(0, 12).map((p, i) => {
    const sym = bareSymbol(p.symbol);
    const mtm = parseMoney(p.mtmPnl ?? p.unrealizedPnl);
    return {
      key: `${sym}-${i}`,
      primary: sym,
      secondary: `Qty ${String(p.qty ?? "—")}`,
      meta: formatPnlDisplay(mtm),
      tone: mtm != null && mtm > 0 ? "positive" : mtm != null && mtm < 0 ? "negative" : "neutral",
    };
  });

  const signalDetail = (rows: IntradaySignalRow[], limit = 10): LiveOpsDetailRow[] =>
    rows.slice(0, limit).map((s, i) => ({
      key: String(s.id ?? s.signalId ?? i),
      primary: bareSymbol(s.symbol),
      secondary: signalStrategyKey(s),
      meta: outcomeOf(s) || executionOf(s) || "—",
      tone: HIT_OUTCOMES.has(outcomeOf(s)) ? "positive" : SL_OUTCOMES.has(outcomeOf(s)) ? "negative" : "neutral",
    }));

  const guardDetails: LiveOpsDetailRow[] = guards.slice(0, 8).map((g, i) => ({
    key: String(g.id ?? g.eventId ?? i),
    primary: String(g.title ?? g.code ?? g.kind ?? "Guard"),
    secondary: String(g.message ?? g.detail ?? "—"),
    tone: "warn" as const,
  }));

  return [
    {
      id: "livePnl",
      label: "Live P&L",
      value: formatPnlDisplay(livePnl),
      sublabel: "MTM · broker-first",
      tone: pnlTone,
      count: openPositions.length,
      details: openDetails.length > 0 ? openDetails : [{ key: "flat", primary: "Flat book", secondary: "No open positions", meta: formatPnlDisplay(livePnl) }],
    },
    {
      id: "tradesHit",
      label: "Trades hit",
      value: String(hits.length),
      sublabel: "Target today",
      tone: hits.length > 0 ? "positive" : "neutral",
      count: hits.length,
      details: signalDetail(hits),
    },
    {
      id: "running",
      label: "Running",
      value: String(Math.max(runningSignals.length, openPositions.length, input.runningStrategies ?? 0)),
      sublabel: "Signals · book",
      tone: "info",
      count: runningSignals.length,
      details: [
        ...signalDetail(runningSignals, 6),
        ...openDetails.slice(0, 6),
      ].slice(0, 10),
    },
    {
      id: "sl",
      label: "SL",
      value: String(slHits.length),
      sublabel: "Stop hits today",
      tone: slHits.length > 0 ? "negative" : "neutral",
      count: slHits.length,
      details: signalDetail(slHits),
    },
    {
      id: "notExecuted",
      label: "Not executed",
      value: String(notExecuted.length + (input.rejectedOrders ?? 0)),
      sublabel: "Skipped · rejected",
      tone: notExecuted.length > 0 ? "warn" : "neutral",
      count: notExecuted.length,
      details: [
        ...signalDetail(notExecuted, 8),
        ...(input.rejectedOrders
          ? [{ key: "oms-rej", primary: "OMS rejected orders", secondary: "Live pipeline", meta: String(input.rejectedOrders), tone: "warn" as const }]
          : []),
      ],
    },
    {
      id: "openBook",
      label: "Open book",
      value: String(openPositions.length),
      sublabel: "Live positions",
      tone: openPositions.length > 0 ? "info" : "neutral",
      count: openPositions.length,
      details: openDetails,
    },
    {
      id: "guards",
      label: "Guards",
      value: String(guards.length),
      sublabel: "Execution events",
      tone: guards.length > 0 ? "warn" : "neutral",
      count: guards.length,
      details: guardDetails,
    },
  ];
}

export function deriveSessionBanner(input: {
  overallStatus?: string;
  sessionState?: string;
  sessionDetail?: string;
  runningStrategies?: number;
  totalStrategies?: number;
  websocketState?: string;
  brokerConnected?: boolean;
  feedHealthy?: boolean;
}): { tone: "ok" | "warn" | "bad"; title: string; subtitle: string } {
  const session = input.sessionState ?? "—";
  const running = input.runningStrategies ?? 0;
  const total = input.totalStrategies ?? 0;
  const ws = input.websocketState ?? "—";
  const livePath =
    session === "MARKET_OPEN" &&
    input.brokerConnected &&
    (input.feedHealthy ?? true) &&
    (ws.includes("OPEN") || ws.includes("CONNECTED"));

  const raw = (input.overallStatus ?? "CHECKING").toUpperCase();
  let tone: "ok" | "warn" | "bad" = raw === "READY" ? "ok" : raw === "WARNING" || raw === "DEGRADED" ? "warn" : "bad";

  if (livePath && raw === "BLOCKED") {
    tone = running > 0 ? "warn" : "bad";
  } else if (livePath && (raw === "WARNING" || raw === "DEGRADED")) {
    tone = "warn";
  } else if (livePath && raw === "READY") {
    tone = "ok";
  }

  const title =
    tone === "ok"
      ? `LIVE — ${input.sessionDetail ?? "Market session"}`
      : tone === "warn"
        ? `PARTIAL — ${input.sessionDetail ?? session}`
        : `${raw} — ${input.sessionDetail ?? session}`;

  const subtitle = `${running}/${total} strategies · WS ${ws}`;
  return { tone, title, subtitle };
}
