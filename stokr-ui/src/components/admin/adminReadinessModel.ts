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

export function platformZerodhaVendor(s: OpsSnapshot | undefined): Record<string, unknown> | undefined {
  return asRecord(asRecord(asRecord(s?.platformMarketFeed)?.vendors)?.ZERODHA);
}

/** Red readiness only when OAuth re-auth is required (no refresh token / AUTH_EXPIRED). */
export function requiresPlatformOAuthIntervention(s: OpsSnapshot | undefined): boolean {
  const z = platformZerodhaVendor(s);
  if (!z) {
    const vendors = asRecord(asRecord(s?.platformMarketFeed)?.vendors) ?? {};
    return Object.keys(vendors).length === 0;
  }
  const conn = String(z.connectionState ?? "").toUpperCase();
  if (conn === "AUTH_EXPIRED") return true;
  const hasRefresh = z.hasRefreshToken === true || z.hasRefreshToken === "true";
  const configured = z.configured === true || z.configured === "true";
  if (!configured && !hasRefresh) return true;
  const detail = String(z.operationalLivePathDetail ?? z.detail ?? "");
  if (!hasRefresh && detail.toLowerCase().includes("no oauth")) return true;
  const vendors = asRecord(asRecord(s?.brokerSessions)?.vendors) ?? {};
  for (const raw of Object.values(vendors)) {
    const v = asRecord(raw);
    if (String(v?.authStatus ?? "").includes("TOKENS_EXPIRED")) return true;
  }
  return false;
}

/** Feed or pipeline is transiently unhealthy but platform auto-heal is expected to recover it. */
export function isOperationalAutoHealing(s: OpsSnapshot | undefined): boolean {
  if (requiresPlatformOAuthIntervention(s)) return false;
  const z = platformZerodhaVendor(s);
  if (z?.reconnecting === true || z?.reconnecting === "true") return true;
  if (!hasPlatformMarketFeedOperational(s)) return true;
  const fresh = asRecord(s?.marketFreshness);
  const freshSt = String(fresh?.status ?? "UNKNOWN").toUpperCase();
  if (freshSt === "STALE") return true;
  return false;
}

/** True when platform OAuth session exists and vendor reports CONNECTED (WS may still be stale). */
export function hasPlatformMarketFeedConnected(s: OpsSnapshot | undefined): boolean {
  const root = asRecord(s?.platformMarketFeed);
  const vendors = asRecord(root?.vendors) ?? {};
  for (const raw of Object.values(vendors)) {
    const v = asRecord(raw);
    const configuredOk = v?.configured === true || v?.configured === "true";
    const st = String(v?.connectionState ?? "").toUpperCase();
    if (st === "CONNECTED" && configuredOk) {
      return true;
    }
  }
  return false;
}

/** True when backend operationalLivePath gate passes (ticks fresh, packets flowing, not paused). */
export function hasPlatformMarketFeedOperational(s: OpsSnapshot | undefined): boolean {
  const root = asRecord(s?.platformMarketFeed);
  const vendors = asRecord(root?.vendors) ?? {};
  for (const raw of Object.values(vendors)) {
    const v = asRecord(raw);
    if (v?.operationalLivePath === true || v?.operationalLivePath === "true") {
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
  const authRequired = requiresPlatformOAuthIntervention(s);
  const autoHealing = isOperationalAutoHealing(s);
  const brokerLive = hasActiveBrokerMarketFeed(s);
  const life = asRecord(s?.operationalLifecycle);
  const tapeReason = life?.platformTapeReason != null ? String(life.platformTapeReason) : "";
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

  const brokerState: ChainLinkState = !dbOk ? "UNAVAILABLE" : authRequired ? "OFFLINE" : "OK";
  const brokerDetail = !dbOk
    ? "Cannot evaluate broker rows — database probe failed."
    : authRequired
      ? "Zerodha OAuth re-auth required in Broker infrastructure."
      : autoHealing
        ? tapeReason || "Platform feed auto-healing (websocket, ticks, or candles catching up)."
        : stale
          ? "Candle store lagging — ingestion auto-heal in progress."
          : platformFed && !traderAccounts
            ? "Platform market feed active. Trader broker_accounts optional."
            : "Market path nominal.";

  const ingestionState: ChainLinkState = !dbOk ? "UNAVAILABLE" : authRequired ? "OFFLINE" : "OK";
  const ingestionDetail = authRequired
    ? "Connect platform OAuth to resume live ingestion."
    : autoHealing || stale
      ? `Ingestion catching up — 1m lag ~ ${fresh?.latest1mLagSeconds ?? mp?.latest1mLagSeconds ?? "-"}s`
      : "Candle store advancing within tolerance.";

  const aggState = ingestionState;
  const aggDetail = ingestionState === "OK" ? "Aggregates follow ingestion plane." : ingestionDetail;

  const scanEngine = String(life?.scannerEngineState ?? "").toUpperCase();
  const scanState: ChainLinkState =
    authRequired ? "OFFLINE" : life && scanEngine === "PAUSED" && autoHealing ? "OK" : running > 0 ? "OK" : "OK";
  const scanDetail = authRequired
    ? "Scanners idle until OAuth is restored."
    : life && scanEngine === "PAUSED"
      ? String(life.scannerPollSkipReason ?? "Scanner poll skipped — platform auto-heal active.")
      : running > 0
        ? `${running} RUNNING strategy instance(s).`
        : "Catalog idle or outside market — auto-heal keeps pipeline warm.";

  const sigEngine = String(life?.signalGenerationState ?? "").toUpperCase();
  const sigState: ChainLinkState = authRequired ? "OFFLINE" : running > 0 ? "OK" : "OK";
  const sigDetail = authRequired
    ? "Live signals blocked until OAuth re-auth."
    : life && sigEngine === "UNAVAILABLE" && autoHealing
      ? "Signal path warming up — platform auto-heal active."
      : scanDetail;

  const stuck = typeof oms?.stuckOrdersApprox === "number" ? oms.stuckOrdersApprox : Number(oms?.stuckOrdersApprox ?? 0);
  const rej = typeof oms?.rejectRateApprox === "number" ? oms.rejectRateApprox : Number(oms?.rejectRateApprox ?? 0);
  const omsState: ChainLinkState = !dbOk ? "UNAVAILABLE" : !redisOk ? "DEGRADED" : "OK";
  const omsDetail =
    !dbOk || !redisOk
      ? "OMS needs PostgreSQL + Redis CONNECTED."
      : stuck > 0
        ? `Stuck orders ~ ${stuck}`
        : `Reject rate ~ ${rej.toFixed(2)}%`;

  const replayState: ChainLinkState = jq > 80 ? "BACKFILLING" : "OK";
  const replayDetail =
    jq > 80
      ? `Replay backlog  ·  queued ${jq}  ·  running ${jr}`
      : `Replay queue  ·  queued ${jq}  ·  running ${jr}`;

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
      subline: "Telemetry has not arrived yet - wait for the readiness strip to populate.",
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
  const life = asRecord(s.operationalLifecycle);
  const tapeOperational = life?.livePathOperational !== false;
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

  if (requiresPlatformOAuthIntervention(s)) {
    return {
      level: "OFFLINE",
      headline: "Zerodha OAuth re-auth required",
      subline:
        "Platform auto-heal cannot refresh tokens. Open Broker infrastructure and complete Connect (Zerodha) before live signals resume.",
      brokerConnected: false,
      killSwitch: kill,
    };
  }

  if (kill) {
    return {
      level: "LIMITED",
      headline: "Kill switch engaged",
      subline: "Global halt is active. Disarm kill switch to resume execution; feeds may still be connected.",
      brokerConnected: true,
      killSwitch: true,
    };
  }

  if (jq > 80) {
    return {
      level: "BACKFILLING",
      headline: "Replay infrastructure saturated",
      subline: `Replay job queue is elevated (queued ${jq}). Live rails may still be up — workers draining backlog.`,
      brokerConnected: true,
      killSwitch: kill,
    };
  }

  if (isOperationalAutoHealing(s) || stale || (life && !tapeOperational)) {
    return {
      level: "READY",
      headline: "Platform auto-healing operational layers",
      subline: String(
        life?.platformTapeReason ??
          (stale
            ? "Candle freshness catching up — scanners and feed heal automatically."
            : "Feed, websocket, and signal pipeline are self-recovering."),
      ),
      brokerConnected: brokerConnected || hasPlatformMarketFeedConnected(s),
      killSwitch: false,
    };
  }

  if (!brokerConnected) {
    return {
      level: "READY",
      headline: "Platform warming market path",
      subline: "Auto-heal is establishing platform feed and broker connectivity — readiness strip stays green while recovery runs.",
      brokerConnected: false,
      killSwitch: kill,
    };
  }

  if (!redisOk) {
    return {
      level: "READY",
      headline: "Platform operationally ready",
      subline: "Redis probe pending or reconnecting — OMS and feeds continue under auto-heal.",
      brokerConnected: true,
      killSwitch: false,
    };
  }

  return {
    level: "READY",
    headline: "Platform operationally ready",
    subline: "Broker feed present, control DB reachable, freshness nominal — continue monitoring the readiness strip.",
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
