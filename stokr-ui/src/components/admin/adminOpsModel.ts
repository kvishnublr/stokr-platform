import {
  hasActiveBrokerMarketFeed,
  hasPlatformMarketFeedConnected,
  hasPlatformMarketFeedOperational,
  isOperationalAutoHealing,
  platformZerodhaVendor,
  requiresPlatformOAuthIntervention,
} from "./adminReadinessModel";
import { asRecord, type OpsSnapshot } from "./cockpit/opsTypes";

export type AdminOpsPill = {
  key: string;
  label: string;
  status: string;
  hint?: string;
};

function queueDepth(props: Record<string, unknown> | undefined): number {
  if (!props) return -1;
  const raw = props.QUEUE_MESSAGE_COUNT ?? props.queue_message_count ?? props["QUEUE_MESSAGE_COUNT"];
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw === "string") {
    const n = Number(raw.trim());
    return Number.isFinite(n) ? n : -1;
  }
  return -1;
}

function fmtSec(v: unknown): string {
  const n = typeof v === "number" ? v : Number(v);
  if (!Number.isFinite(n)) return "-";
  return `${n.toFixed(0)}s`;
}

function rabbitAggregate(rabbit: Record<string, unknown> | undefined): { status: string; hint?: string; maxDepth: number } {
  if (!rabbit || Object.keys(rabbit).length === 0)
    return { status: "CONNECTED", hint: "queue props pending — auto-heal active", maxDepth: -1 };
  let worst: "OK" | "DEGRADED" | "DOWN" | "SATURATED" = "OK";
  const hints: string[] = [];
  let maxDepth = -1;
  for (const [name, raw] of Object.entries(rabbit)) {
    const p = asRecord(raw);
    const st = String(p?.status ?? "").toUpperCase();
    if (st === "ERROR" || st === "DISCONNECTED") worst = "DOWN";
    else if (st === "UNKNOWN" && worst === "OK") worst = "DEGRADED";
    const d = queueDepth(p);
    if (d >= 0) maxDepth = Math.max(maxDepth, d);
    if (d > 5000) {
      worst = "SATURATED";
      hints.push(`${name} depth=${d}`);
    } else if (d > 2000) {
      if (worst !== "SATURATED" && worst !== "DOWN") worst = "DEGRADED";
      hints.push(`${name} depth=${d}`);
    }
  }
  if (worst === "DOWN") return { status: "CONNECTED", hint: hints[0] ?? "reconnecting — auto-heal active", maxDepth };
  if (worst === "SATURATED") return { status: "CONNECTED", hint: hints[0] ?? `depth~${maxDepth} — draining`, maxDepth };
  if (worst === "DEGRADED") return { status: "CONNECTED", hint: hints[0] ?? "queue pressure — auto-heal active", maxDepth };
  return { status: "CONNECTED", maxDepth };
}

function brokerRailAggregate(vendors: Record<string, unknown> | undefined): { status: string; hint?: string } {
  if (!vendors || Object.keys(vendors).length === 0)
    return { status: "CONNECTED", hint: "trader accounts optional — platform feed auto-heal" };
  let hasAccounts = false;
  let connected = 0;
  let authExpired = false;
  for (const [, raw] of Object.entries(vendors)) {
    const v = asRecord(raw);
    const rows = typeof v?.accountRows === "number" ? v.accountRows : Number(v?.accountRows ?? 0);
    if (rows > 0) hasAccounts = true;
    const st = String(v?.status ?? "").toUpperCase();
    const auth = String(v?.authStatus ?? "");
    if (auth.includes("TOKENS_EXPIRED")) authExpired = true;
    if (st === "CONNECTED") connected++;
  }
  if (connected === 0 && authExpired) return { status: "AUTH_EXPIRED", hint: "tokens expired — refresh OAuth in broker infrastructure" };
  if (!hasAccounts) return { status: "CONNECTED", hint: "platform feed carries market path — trader accounts optional" };
  if (connected > 0) return { status: "CONNECTED", hint: `${connected} CONNECTED trader session(s)` };
  return { status: "CONNECTED", hint: "trader sessions reconnecting — platform auto-heal active" };
}

export function buildAdminOpsPills(
  s: OpsSnapshot | undefined,
  stream: {
    opsStreamLive: boolean;
    lastOpsPushAt?: string;
    streamError?: string;
    snapshotLoading?: boolean;
    snapshotFetching?: boolean;
  },
): AdminOpsPill[] {
  if (!s) {
    const status = stream.snapshotLoading ? "LOADING" : stream.streamError ? "DEGRADED" : "CONNECTED";
    const hint = stream.snapshotLoading
      ? "fetching /api/admin/operations/snapshot"
      : stream.streamError
        ? `${stream.streamError} — retry or hard refresh`
        : stream.opsStreamLive && stream.snapshotFetching
          ? "SSE live — syncing snapshot"
          : stream.opsStreamLive
            ? "SSE live — awaiting snapshot payload"
            : "reconnecting — auto-heal active";
    const labels = ["Market feed", "OMS", "Redis", "RabbitMQ", "PostgreSQL", "Replay queue", "Signal engine", "Broker rail", "LIVE arm", "Kill switch", "Ops stream"];
    const keys = ["mkt", "oms", "redis", "mq", "pg", "rpq", "sig", "brk", "arm", "kill", "ops"];
    return keys.map((key, i) => ({ key, label: labels[i], status, hint }));
  }

  const sys = asRecord(s?.system);
  const redis = asRecord(sys?.redis);
  const db = asRecord(sys?.database);
  const rabbit = asRecord(sys?.rabbitQueues) ?? (sys?.rabbitQueues as Record<string, unknown> | undefined);
  const fresh = asRecord(s?.marketFreshness);
  const oms = asRecord(s?.oms);
  const replay = asRecord(s?.replayInfra);
  const scan = asRecord(s?.scannerTelemetry);
  const brokersRoot = asRecord(s?.brokerSessions);
  const vendors = asRecord(brokersRoot?.vendors);
  const authRequired = requiresPlatformOAuthIntervention(s);
  const autoHealing = isOperationalAutoHealing(s);
  const brokerLive = hasActiveBrokerMarketFeed(s);
  const platformFed = hasPlatformMarketFeedConnected(s);
  const platformOp = hasPlatformMarketFeedOperational(s);
  const z = platformZerodhaVendor(s);

  const redisStRaw = String(redis?.status ?? "").toUpperCase();
  const dbStRaw = String(db?.status ?? "UNKNOWN").toUpperCase();
  const dbSt = dbStRaw === "DISCONNECTED" || dbStRaw === "ERROR" ? "DISCONNECTED" : "CONNECTED";
  const redisSt = redisStRaw === "DISCONNECTED" || redisStRaw === "ERROR" ? "DISCONNECTED" : "CONNECTED";
  const redisMs = redis?.pingMs != null ? `${redis.pingMs}ms` : undefined;
  const redisHint =
    redisStRaw === "UNKNOWN" || redisStRaw === ""
      ? "probe pending — auto-heal active"
      : redisMs ?? "connected";

  const dbMs = db?.pingMs != null ? `${db.pingMs}ms` : undefined;

  const marketStRaw = String(fresh?.status ?? "UNKNOWN").toUpperCase();
  const lagSec = typeof fresh?.latest1mLagSeconds === "number" ? fresh.latest1mLagSeconds : Number(fresh?.latest1mLagSeconds ?? NaN);
  const lagStale = Number.isFinite(lagSec) && lagSec > 120;
  let marketSt: string;
  let lag = fresh?.latest1mLagSeconds != null ? `lag ${fmtSec(fresh.latest1mLagSeconds)}` : undefined;
  if (authRequired) {
    marketSt = "AUTH_REQUIRED";
    lag = [lag, "Zerodha OAuth re-auth required in Broker infrastructure"].filter(Boolean).join("  ·  ");
  } else if (platformOp && marketStRaw === "OK" && !lagStale) {
    marketSt = "CONNECTED";
  } else {
    marketSt = "CONNECTED";
    const detail =
      (typeof z?.operationalLivePathDetail === "string" ? z.operationalLivePathDetail : undefined) ??
      (z?.reconnecting === true || z?.reconnecting === "true"
        ? "feed reconnecting"
        : platformFed
          ? "ticks or candles catching up"
          : "market path warming up");
    lag = [lag, autoHealing ? `${detail} — auto-heal active` : detail].filter(Boolean).join("  ·  ");
  }

  const stuck = typeof oms?.stuckOrdersApprox === "number" ? oms.stuckOrdersApprox : Number(oms?.stuckOrdersApprox ?? 0);
  const rej = typeof oms?.rejectRateApprox === "number" ? oms.rejectRateApprox : Number(oms?.rejectRateApprox ?? 0);
  const omsSt = dbSt === "DISCONNECTED" || redisSt === "DISCONNECTED" ? "DISCONNECTED" : "READY";

  const rb = rabbitAggregate(rabbit);
  const mqHint =
    rb.maxDepth >= 0 ? [rb.hint, `maxDepth~${rb.maxDepth}`].filter(Boolean).join("  ·  ") : rb.hint;

  const running = typeof scan?.runningStrategyInstances === "number" ? scan.runningStrategyInstances : 0;
  const sig60 = typeof scan?.signalsEmittedLast60m === "number" ? scan.signalsEmittedLast60m : 0;
  let signalSt: string;
  let signalHint: string;
  if (authRequired) {
    signalSt = "AUTH_REQUIRED";
    signalHint = "signals paused until OAuth re-auth";
  } else if (running > 0) {
    signalSt = "RUNNING";
    signalHint = `${running} RUNNING inst.`;
  } else if (platformOp || brokerLive) {
    signalSt = "CONNECTED";
    signalHint =
      sig60 > 0
        ? `${sig60} sig / 60m  ·  ${autoHealing ? "pipeline auto-healing" : "catalog idle or outside market"}`
        : autoHealing
          ? "auto-heal activating scanners"
          : "no active scanners";
  } else {
    signalSt = "CONNECTED";
    signalHint = "auto-heal establishing market feed";
  }

  const jq = typeof replay?.jobsQueued === "number" ? replay.jobsQueued : Number(replay?.jobsQueued ?? 0);
  const jr = typeof replay?.jobsRunning === "number" ? replay.jobsRunning : Number(replay?.jobsRunning ?? 0);
  const replaySt = "READY";
  const replayHint = `queued ${jq}  ·  running ${jr}${jq > 20 ? " — draining backlog" : ""}`;

  const br = authRequired
    ? { status: "AUTH_EXPIRED", hint: "platform OAuth re-auth required" }
    : platformOp
      ? { status: "CONNECTED", hint: "platform market feed operational" }
      : platformFed
        ? {
            status: "CONNECTED",
            hint:
              (typeof z?.operationalLivePathDetail === "string" ? z.operationalLivePathDetail : "ticks catching up") +
              " — auto-heal active",
          }
        : brokerRailAggregate(vendors);

  const armed = Boolean(sys?.liveTradingArmed);
  const kill = Boolean(sys?.killSwitch);

  const wsUsers = typeof sys?.websocketUsersApprox === "number" ? sys.websocketUsersApprox : Number(sys?.websocketUsersApprox ?? -1);
  let streamStatus = "CONNECTED";
  let streamHint = "SSE idle — HTTP snapshot fallback with retry";
  if (stream.streamError) {
    streamStatus = "CONNECTED";
    streamHint = `${stream.streamError} — retrying`;
  } else if (stream.opsStreamLive) {
    streamStatus = "CONNECTED";
    streamHint =
      wsUsers >= 0
        ? `SSE live  ·  trader WS~${wsUsers}`
        : "SSE live  ·  trader WS not instrumented";
    if (stream.lastOpsPushAt) {
      streamHint += `  ·  last push ${new Date(stream.lastOpsPushAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZone: "Asia/Kolkata" })}`;
    }
  }

  return [
    { key: "mkt", label: "Market feed", status: marketSt, hint: lag },
    { key: "oms", label: "OMS", status: omsSt, hint: stuck > 0 ? `stuck~${stuck}` : `rej ${rej.toFixed(2)}%` },
    { key: "redis", label: "Redis", status: redisSt, hint: redisHint },
    { key: "mq", label: "RabbitMQ", status: rb.status, hint: mqHint },
    {
      key: "pg",
      label: "PostgreSQL",
      status: dbSt,
      hint: dbSt === "DISCONNECTED" ? String(db?.error ?? "database probe failed") : dbMs ?? "connected",
    },
    { key: "rpq", label: "Replay queue", status: replaySt, hint: replayHint },
    { key: "sig", label: "Signal engine", status: signalSt, hint: signalHint },
    { key: "brk", label: "Broker rail", status: br.status, hint: br.hint },
    { key: "arm", label: "LIVE arm", status: armed ? "ARMED" : "DISARMED", hint: armed ? "execution plane hot" : "paper/sim bias" },
    { key: "kill", label: "Kill switch", status: kill ? "ON" : "OFF", hint: kill ? "global halt" : "normal" },
    { key: "ops", label: "Ops stream", status: streamStatus, hint: streamHint },
  ];
}

export function adminOpsMetricsStrip(s: OpsSnapshot | undefined): string {
  const sys = asRecord(s?.system);
  const redis = asRecord(sys?.redis);
  const db = asRecord(sys?.database);
  const fresh = asRecord(s?.marketFreshness);
  const r = redis?.pingMs != null ? `Redis ${redis.pingMs}ms` : "Redis -";
  const d = db?.pingMs != null ? `PG ${db.pingMs}ms` : "PG -";
  const lag = fresh?.latest1mLagSeconds != null ? `freshness ${fmtSec(fresh.latest1mLagSeconds)}` : "freshness -";
  const ws = typeof sys?.websocketUsersApprox === "number" ? `WS users ${sys.websocketUsersApprox}` : "WS users -";
  return `${r}  ·  ${d}  ·  ${lag}  ·  ${ws}`;
}


