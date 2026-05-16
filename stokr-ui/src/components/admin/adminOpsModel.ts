import { hasActiveBrokerMarketFeed, hasPlatformMarketFeedConnected } from "./adminReadinessModel";
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
    return { status: "DEGRADED", hint: "Rabbit queue props not in snapshot - workers may still be healthy", maxDepth: -1 };
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
  if (worst === "DOWN") return { status: "DISCONNECTED", hint: hints[0], maxDepth };
  if (worst === "SATURATED") return { status: "SATURATED", hint: hints[0] ?? `depthâ‰¤${maxDepth}`, maxDepth };
  if (worst === "DEGRADED") return { status: "DEGRADED", hint: hints[0], maxDepth };
  return { status: "CONNECTED", maxDepth };
}

function brokerRailAggregate(vendors: Record<string, unknown> | undefined): { status: string; hint?: string } {
  if (!vendors || Object.keys(vendors).length === 0) return { status: "OFFLINE", hint: "no vendor snapshot" };
  let hasAccounts = false;
  let connected = 0;
  let degraded = 0;
  let authExpired = false;
  for (const [, raw] of Object.entries(vendors)) {
    const v = asRecord(raw);
    const rows = typeof v?.accountRows === "number" ? v.accountRows : Number(v?.accountRows ?? 0);
    if (rows > 0) hasAccounts = true;
    const st = String(v?.status ?? "").toUpperCase();
    const auth = String(v?.authStatus ?? "");
    if (auth.includes("TOKENS_EXPIRED")) authExpired = true;
    if (st === "CONNECTED") connected++;
    if (st === "DEGRADED") degraded++;
  }
  if (!hasAccounts) return { status: "OFFLINE", hint: "no broker_accounts rows" };
  if (connected === 0 && authExpired) return { status: "AUTH_EXPIRED", hint: "tokens expired - refresh OAuth" };
  if (degraded > 0 && connected === 0) return { status: "DEGRADED", hint: "sessions unhealthy" };
  if (connected > 0) return { status: "CONNECTED" };
  return { status: "OFFLINE", hint: "no CONNECTED sessions" };
}

export function buildAdminOpsPills(
  s: OpsSnapshot | undefined,
  stream: { opsStreamLive: boolean; lastOpsPushAt?: string; streamError?: string },
): AdminOpsPill[] {
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
  const brokerLive = hasActiveBrokerMarketFeed(s);

  const redisStRaw = String(redis?.status ?? "").toUpperCase();
  const dbSt = String(db?.status ?? "UNKNOWN").toUpperCase();
  const redisSt = redisStRaw === "CONNECTED" ? "CONNECTED" : redisStRaw === "UNKNOWN" || redisStRaw === "" ? "DEGRADED" : "DISCONNECTED";
  const redisMs = redis?.pingMs != null ? `${redis.pingMs}ms` : undefined;
  const redisHint = redisStRaw === "UNKNOWN" || redisStRaw === "" ? "probe missing - treat as degraded" : redisMs;

  const dbMs = db?.pingMs != null ? `${db.pingMs}ms` : undefined;

  const marketStRaw = String(fresh?.status ?? "UNKNOWN").toUpperCase();
  let marketSt: string;
  let lag = fresh?.latest1mLagSeconds != null ? `lag ${fmtSec(fresh.latest1mLagSeconds)}` : undefined;
  if (!brokerLive) {
    marketSt = "OFFLINE";
    lag = [lag, "no CONNECTED broker sessions"].filter(Boolean).join("  ·  ");
  } else if (marketStRaw === "OK") {
    marketSt = "CONNECTED";
  } else if (marketStRaw === "STALE") {
    marketSt = "STALE";
  } else {
    marketSt = "DEGRADED";
  }

  const stuck = typeof oms?.stuckOrdersApprox === "number" ? oms.stuckOrdersApprox : Number(oms?.stuckOrdersApprox ?? 0);
  const rej = typeof oms?.rejectRateApprox === "number" ? oms.rejectRateApprox : Number(oms?.rejectRateApprox ?? 0);
  let omsSt = "READY";
  if (redisSt !== "CONNECTED" || dbSt !== "CONNECTED") omsSt = "DEGRADED";
  else if (stuck > 0 || rej > 5) omsSt = "DEGRADED";

  const rb = rabbitAggregate(rabbit);
  const mqHint =
    rb.maxDepth >= 0 ? [rb.hint, `maxDepth~${rb.maxDepth}`].filter(Boolean).join("  ·  ") : rb.hint;

  const running = typeof scan?.runningStrategyInstances === "number" ? scan.runningStrategyInstances : 0;
  const sig60 = typeof scan?.signalsEmittedLast60m === "number" ? scan.signalsEmittedLast60m : 0;
  let signalSt: string;
  let signalHint: string;
  if (!brokerLive) {
    signalSt = "PAUSED";
    signalHint = "no broker feed";
  } else if (running > 0) {
    signalSt = "RUNNING";
    signalHint = `${running} RUNNING inst.`;
  } else if (sig60 > 0) {
    signalSt = "DEGRADED";
    signalHint = `${sig60} sig / 60m  ·  no RUNNING`;
  } else {
    signalSt = "IDLE";
    signalHint = "no active scanners";
  }

  const jq = typeof replay?.jobsQueued === "number" ? replay.jobsQueued : Number(replay?.jobsQueued ?? 0);
  const jr = typeof replay?.jobsRunning === "number" ? replay.jobsRunning : Number(replay?.jobsRunning ?? 0);
  let replaySt: string;
  if (jq > 80) replaySt = "BACKFILLING";
  else if (jq > 20) replaySt = "DEGRADED";
  else if (!brokerLive) replaySt = "WAITING_FOR_MARKET_DATA";
  else replaySt = "READY";
  const replayHint = `queued ${jq}  ·  running ${jr}`;

  const br = hasPlatformMarketFeedConnected(s)
    ? { status: "CONNECTED", hint: "platform market feed session (admin OAuth)" }
    : brokerRailAggregate(vendors);

  const armed = Boolean(sys?.liveTradingArmed);
  const kill = Boolean(sys?.killSwitch);

  const wsUsers = typeof sys?.websocketUsersApprox === "number" ? sys.websocketUsersApprox : Number(sys?.websocketUsersApprox ?? -1);
  let streamStatus = "DEGRADED";
  let streamHint = "SSE idle - HTTP snapshot fallback";
  if (stream.streamError) {
    streamStatus = "DISCONNECTED";
    streamHint = stream.streamError;
  } else if (stream.opsStreamLive) {
    streamStatus = "CONNECTED";
    streamHint =
      wsUsers >= 0
        ? `SSE live  ·  trader WS~${wsUsers}`
        : "SSE live  ·  trader WS not instrumented";
    if (stream.lastOpsPushAt) {
      streamHint += `  ·  last push ${new Date(stream.lastOpsPushAt).toLocaleTimeString()}`;
    }
  }

  return [
    { key: "mkt", label: "Market feed", status: marketSt, hint: lag },
    { key: "oms", label: "OMS", status: omsSt, hint: stuck > 0 ? `stuck~${stuck}` : `rej ${rej.toFixed(2)}%` },
    { key: "redis", label: "Redis", status: redisSt, hint: redisHint },
    { key: "mq", label: "RabbitMQ", status: rb.status, hint: mqHint },
    { key: "pg", label: "PostgreSQL", status: dbSt === "CONNECTED" ? "CONNECTED" : "DISCONNECTED", hint: dbMs },
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


