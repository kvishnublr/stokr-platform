import { asArray, asRecord, type OpsSnapshot } from "./cockpit/opsTypes";

/** Canonical vendor keys from `BrokerSessionRegistryService`. */
export const SUPPORTED_BROKER_VENDORS = ["ZERODHA", "DHAN", "UPSTOX", "ANGEL"] as const;

/** Display order for the broker control center (ops UX). */
export const BROKER_CONTROL_CENTER_ORDER = ["ZERODHA", "UPSTOX", "ANGEL", "DHAN"] as const;
export type SupportedBrokerVendor = (typeof SUPPORTED_BROKER_VENDORS)[number];

export function vendorDisplayName(vendor: string): string {
  switch (vendor.toUpperCase()) {
    case "ZERODHA":
      return "Zerodha";
    case "DHAN":
      return "Dhan";
    case "UPSTOX":
      return "Upstox";
    case "ANGEL":
      return "Angel One";
    default:
      return vendor;
  }
}

/** True when any platform vendor has a live market path (prefers backend operationalLivePath when set). */
export function hasPlatformMarketFeedConnected(s: OpsSnapshot | undefined): boolean {
  const root = asRecord(s?.platformMarketFeed);
  const vendors = asRecord(root?.vendors) ?? {};
  for (const raw of Object.values(vendors)) {
    const v = asRecord(raw);
    const op = v?.operationalLivePath;
    if (op === true || op === "true") return true;
    if (op === false || op === "false") continue;
    const cfg = v?.configured;
    const configuredOk = cfg === true || cfg === "true";
    const st = String(v?.connectionState ?? "").toUpperCase();
    if (st === "CONNECTED" && configuredOk) {
      return true;
    }
  }
  return false;
}

/** True when at least one non-deleted broker account row is in CONNECTED status (authoritative for this build). */
export function hasActiveBrokerMarketFeed(s: OpsSnapshot | undefined): boolean {
  if (hasPlatformMarketFeedConnected(s)) return true;
  const root = asRecord(s?.brokerSessions);
  const vendors = asRecord(root?.vendors) ?? {};
  for (const raw of Object.values(vendors)) {
    const v = asRecord(raw);
    const n = typeof v?.connectedRows === "number" ? v.connectedRows : Number(v?.connectedRows ?? 0);
    if (Number.isFinite(n) && n > 0) return true;
  }
  return false;
}

export function anyBrokerAccountRows(s: OpsSnapshot | undefined): boolean {
  const root = asRecord(s?.brokerSessions);
  const vendors = asRecord(root?.vendors) ?? {};
  for (const raw of Object.values(vendors)) {
    const v = asRecord(raw);
    const n = typeof v?.accountRows === "number" ? v.accountRows : Number(v?.accountRows ?? 0);
    if (Number.isFinite(n) && n > 0) return true;
  }
  return false;
}

export type ReadinessLevel = "READY" | "DEGRADED" | "LIMITED" | "OFFLINE" | "BACKFILLING";

export type ChainLinkState = "OK" | "OFFLINE" | "DEGRADED" | "PAUSED" | "UNAVAILABLE" | "BACKFILLING";

export type DependencyStep = {
  id: string;
  label: string;
  state: ChainLinkState;
  detail: string;
};

export function buildDependencyChain(s: OpsSnapshot | undefined): DependencyStep[] {
  const brokerLive = hasActiveBrokerMarketFeed(s);
  const fresh = asRecord(s?.marketFreshness);
  const mp = asRecord(s?.marketPlane);
  const scan = asRecord(s?.scannerTelemetry);
  const oms = asRecord(s?.oms);
  const replay = asRecord(s?.replayInfra);
  const sys = asRecord(s?.system);
  const redis = asRecord(sys?.redis);
  const db = asRecord(sys?.database);

  const redisOk = String(redis?.status ?? "").toUpperCase() === "CONNECTED";
  const dbOk = String(db?.status ?? "").toUpperCase() === "CONNECTED";
  const freshSt = String(fresh?.status ?? mp?.freshnessStatus ?? "UNKNOWN").toUpperCase();
  const stale = freshSt === "STALE";
  const running = typeof scan?.runningStrategyInstances === "number" ? scan.runningStrategyInstances : Number(scan?.runningStrategyInstances ?? 0);
  const jq = typeof replay?.jobsQueued === "number" ? replay.jobsQueued : Number(replay?.jobsQueued ?? 0);
  const jr = typeof replay?.jobsRunning === "number" ? replay.jobsRunning : Number(replay?.jobsRunning ?? 0);

  const platformFed = hasPlatformMarketFeedConnected(s);
  const traderAccounts = anyBrokerAccountRows(s);

  const brokerState: ChainLinkState = !dbOk
    ? "UNAVAILABLE"
    : !brokerLive
      ? "OFFLINE"
      : stale
        ? "DEGRADED"
        : "OK";
  const brokerDetail = !dbOk
    ? "Cannot evaluate broker rows — database probe failed."
    : !brokerLive
      ? "No active market pipe: connect platform feed (admin OAuth) and/or trader broker_accounts with CONNECTED sessions."
      : stale
        ? "Sessions connected but candle store is stale vs wall clock."
        : platformFed && !traderAccounts
          ? "Platform market feed session active (admin OAuth). Trader execution broker_accounts optional for this plane."
          : "OAuth sessions connected; ingestion may proceed.";

  const ingestionState: ChainLinkState = !dbOk ? "UNAVAILABLE" : !brokerLive ? "OFFLINE" : stale ? "DEGRADED" : "OK";
  const ingestionDetail = !brokerLive
    ? "Live candles require an active market pipe (platform feed OAuth and/or CONNECTED trader broker_accounts)."
    : stale
      ? `1m store lag ≈ ${fresh?.latest1mLagSeconds ?? mp?.latest1mLagSeconds ?? "—"}s`
      : "Candle store advancing within tolerance.";

  const aggState = ingestionState === "OK" ? "OK" : ingestionState;
  const aggDetail = ingestionState === "OK" ? "Aggregates follow ingestion plane (same freshness probe)." : ingestionDetail;

  const scanState: ChainLinkState = !brokerLive ? "PAUSED" : running > 0 ? "OK" : "DEGRADED";
  const scanDetail = !brokerLive
    ? "Scanners cannot consume live ticks without broker connectivity."
    : running > 0
      ? `${running} RUNNING strategy instance(s).`
      : "No RUNNING scanners — catalog idle or schedules outside market.";

  const sigState: ChainLinkState = !brokerLive ? "PAUSED" : running > 0 ? "OK" : "DEGRADED";
  const sigDetail = !brokerLive ? "Signals are not emitted on live rails without broker feed." : scanDetail;

  const stuck = typeof oms?.stuckOrdersApprox === "number" ? oms.stuckOrdersApprox : Number(oms?.stuckOrdersApprox ?? 0);
  const rej = typeof oms?.rejectRateApprox === "number" ? oms.rejectRateApprox : Number(oms?.rejectRateApprox ?? 0);
  let omsState: ChainLinkState = "OK";
  if (!redisOk || !dbOk) omsState = "DEGRADED";
  else if (stuck > 0 || rej > 5) omsState = "DEGRADED";
  const omsDetail =
    !redisOk || !dbOk
      ? "OMS plane needs Redis + PostgreSQL CONNECTED."
      : stuck > 0
        ? `Stuck orders ≈ ${stuck}`
        : `Reject rate ≈ ${rej.toFixed(2)}%`;

  let replayState: ChainLinkState = jq > 80 ? "BACKFILLING" : "OK";
  if (!brokerLive && replayState === "OK") replayState = "DEGRADED";
  const replayDetail =
    jq > 80
      ? `Replay backlog · queued ${jq} · running ${jr}`
      : !brokerLive
        ? "Replay can run historically; live freshness coupling is degraded without broker feed."
        : `Replay queue idle · queued ${jq} · running ${jr}`;

  return [
    { id: "brk", label: "Broker feed", state: brokerState, detail: brokerDetail },
    { id: "ing", label: "Market ingestion", state: ingestionState, detail: ingestionDetail },
    { id: "agg", label: "Aggregation", state: aggState, detail: aggDetail },
    { id: "scn", label: "Scanner engine", state: scanState, detail: scanDetail },
    { id: "sig", label: "Signal distribution", state: sigState, detail: sigDetail },
    { id: "oms", label: "OMS routing", state: omsState, detail: omsDetail },
    { id: "rpl", label: "Replay infra", state: replayState, detail: replayDetail },
  ];
}

export function computeSystemReadiness(s: OpsSnapshot | undefined): {
  level: ReadinessLevel;
  headline: string;
  subline: string;
  brokerConnected: boolean;
  killSwitch: boolean;
} {
  if (!s) {
    return {
      level: "LIMITED",
      headline: "Operations snapshot loading",
      subline: "Telemetry has not arrived yet — wait for the readiness strip to populate.",
      brokerConnected: false,
      killSwitch: false,
    };
  }

  const sys = asRecord(s.system);
  const db = asRecord(sys?.database);
  const redis = asRecord(sys?.redis);
  const kill = Boolean(sys?.killSwitch);
  const dbOk = String(db?.status ?? "").toUpperCase() === "CONNECTED";
  const redisOk = String(redis?.status ?? "").toUpperCase() === "CONNECTED";
  const brokerConnected = hasActiveBrokerMarketFeed(s);
  const fresh = asRecord(s.marketFreshness);
  const mp = asRecord(s.marketPlane);
  const freshSt = String(fresh?.status ?? mp?.freshnessStatus ?? "UNKNOWN").toUpperCase();
  const stale = freshSt === "STALE";
  const replay = asRecord(s.replayInfra);
  const jq = typeof replay?.jobsQueued === "number" ? replay.jobsQueued : Number(replay?.jobsQueued ?? 0);

  if (!dbOk) {
    return {
      level: "OFFLINE",
      headline: "Control plane database offline",
      subline:
        "PostgreSQL is not CONNECTED in the admin probe. Broker sessions and OMS correlation cannot be trusted until DB is restored.",
      brokerConnected,
      killSwitch: kill,
    };
  }

  if (!brokerConnected) {
    return {
      level: "OFFLINE",
      headline: "Live market infrastructure offline",
      subline:
        "No active market pipe. Open Broker infrastructure to establish the platform feed (admin OAuth), and/or connect trader broker_accounts until at least one vendor shows CONNECTED sessions in the operations snapshot.",
      brokerConnected: false,
      killSwitch: kill,
    };
  }

  if (jq > 80) {
    return {
      level: "BACKFILLING",
      headline: "Replay infrastructure saturated",
      subline: `Replay job queue is elevated (queued ${jq}). Live rails may still be up — prioritize worker capacity and failure triage.`,
      brokerConnected: true,
      killSwitch: kill,
    };
  }

  if (kill) {
    return {
      level: "LIMITED",
      headline: "Kill switch engaged",
      subline: "Global halt is active. Broker feeds may still be connected — execution plane is blocked until operations disarms.",
      brokerConnected: true,
      killSwitch: true,
    };
  }

  if (!redisOk) {
    return {
      level: "LIMITED",
      headline: "Core cache probe not healthy",
      subline: "Redis is not reporting CONNECTED. OMS throughput and arm semantics may be degraded even with broker OAuth intact.",
      brokerConnected: true,
      killSwitch: false,
    };
  }

  if (stale) {
    return {
      level: "DEGRADED",
      headline: "Market data freshness degraded",
      subline: "Broker sessions are connected but the candle store is STALE vs wall clock. Scanners and signals may skew until ingestion catches up.",
      brokerConnected: true,
      killSwitch: false,
    };
  }

  return {
    level: "READY",
    headline: "Platform operationally ready",
    subline: "Broker feed present, control DB reachable, freshness nominal — continue monitoring the readiness strip and incidents.",
    brokerConnected: true,
    killSwitch: false,
  };
}

export function worstSymbolsFromSnapshot(s: OpsSnapshot | undefined): Array<Record<string, unknown>> {
  const mp = asRecord(s?.marketPlane);
  const fresh = asRecord(s?.marketFreshness);
  const a = asArray(mp?.worstSymbols1m) ?? asArray(fresh?.worstSymbols1m) ?? [];
  return a.map((x) => asRecord(x) ?? {}).filter((r) => Object.keys(r).length > 0);
}
