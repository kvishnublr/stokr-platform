import { Link } from "react-router-dom";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../../lib/adminQueryKeys";
import { BROKER_CONTROL_CENTER_ORDER, hasActiveBrokerMarketFeed, vendorDisplayName, worstSymbolsFromSnapshot } from "../adminReadinessModel";
import { OpsPanel } from "./OpsPanel";
import { asArray, asRecord, badgeClassForStatus, fmtInt, fmtNum, type OpsSnapshot } from "./opsTypes";

const LS_RESOLVED = "stokr-ops-incidents-resolved";
const LS_MUTED = "stokr-ops-incidents-muted";
const SS_ACK = "stokr-ops-incidents-ack";

function loadIds(key: string): Set<string> {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return new Set();
    const a = JSON.parse(raw) as unknown;
    if (!Array.isArray(a)) return new Set();
    return new Set(a.map(String));
  } catch {
    return new Set();
  }
}

function saveIds(key: string, ids: Set<string>) {
  localStorage.setItem(key, JSON.stringify([...ids]));
}

function loadAck(): Set<string> {
  try {
    const raw = sessionStorage.getItem(SS_ACK);
    if (!raw) return new Set();
    const a = JSON.parse(raw) as unknown;
    if (!Array.isArray(a)) return new Set();
    return new Set(a.map(String));
  } catch {
    return new Set();
  }
}

function saveAck(ids: Set<string>) {
  sessionStorage.setItem(SS_ACK, JSON.stringify([...ids]));
}

function incidentId(row: Record<string, unknown>): string {
  return `${String(row.code ?? "")}:${String(row.detectedAt ?? "")}`;
}

function notWired(action: string) {
  toast.message(`${action} - not exposed on admin HTTP API in this monolith build.`);
}

function queuePropsRow(props: Record<string, unknown> | undefined) {
  if (!props) return "-";
  const depth = props.QUEUE_MESSAGE_COUNT ?? props.queue_message_count;
  const cons = props.CONSUMER_COUNT ?? props.consumer_count;
  const parts: string[] = [];
  if (depth != null) parts.push(`depth ${String(depth)}`);
  if (cons != null) parts.push(`consumers ${String(cons)}`);
  const st = props.status != null ? String(props.status) : "";
  if (st) parts.push(st);
  return parts.length ? parts.join("  ·  ") : JSON.stringify(props).slice(0, 120);
}

function queueDepth(props: Record<string, unknown> | undefined): number {
  if (!props) return -1;
  const raw = props.QUEUE_MESSAGE_COUNT ?? props.queue_message_count;
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw === "string") {
    const n = Number(raw.trim());
    return Number.isFinite(n) ? n : -1;
  }
  return -1;
}

export function BrokerInfrastructureGrid({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const qc = useQueryClient();
  const root = asRecord(snapshot?.brokerSessions);
  const vendors = asRecord(root?.vendors) ?? {};
  const vendorKeys = BROKER_CONTROL_CENTER_ORDER;
  const [userPick, setUserPick] = useState<Record<string, string>>({});

  const invalidate = useCallback(() => {
    void qc.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
  }, [qc]);

  const runZerodha = useCallback(
    async (path: string, body: Record<string, unknown>) => {
      try {
        const res = await api.post(path, body);
        toast.success(String((res.data as { data?: { message?: string; ok?: boolean } })?.data?.message ?? "OK"));
        invalidate();
      } catch (e) {
        toast.error(parseAxiosMessage(e));
      }
    },
    [invalidate],
  );

  return (
    <OpsPanel
      title="Broker infrastructure"
      subtitle="Per-vendor OAuth session plane. Zerodha actions call admin orchestration APIs (user-scoped - pick trader UUID)."
    >
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        {vendorKeys.map((vk) => {
          const v = asRecord(vendors[vk]) ?? {};
          const status = String(v.status ?? "UNKNOWN").toUpperCase();
          const samples = (asArray(v.sampleUserIds) ?? []).map(String);
          const uid = userPick[vk] ?? samples[0] ?? "";
          return (
            <div key={vk} className="rounded-lg border border-border bg-background/60 p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-bold tracking-wide text-foreground">{vendorDisplayName(vk)}</span>
                <span className={`rounded border px-1.5 py-0.5 font-mono text-[10px] ${badgeClassForStatus(status)}`}>{status}</span>
              </div>
              <div className="mt-2 space-y-1">
                <label className="block text-[9px] font-semibold uppercase tracking-wide text-muted-foreground">Target user</label>
                <select
                  className="w-full rounded border border-border bg-card px-2 py-1 font-mono text-[10px] text-foreground"
                  value={uid}
                  onChange={(e) => setUserPick((m) => ({ ...m, [vk]: e.target.value }))}
                >
                  {samples.length === 0 ? <option value="">(no accounts)</option> : null}
                  {samples.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
                <div className="text-[9px] text-muted-foreground">Paused flags: {fmtInt(v.adminFeedPausedAccounts)}</div>
              </div>
              <dl className="mt-2 space-y-1 font-mono text-[10px] text-muted-foreground">
                <div className="flex justify-between gap-2">
                  <dt>WS state</dt>
                  <dd className="text-foreground">{String(v.websocketStatus ?? "UNKNOWN")}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Auth</dt>
                  <dd className="truncate text-foreground" title={String(v.authStatus ?? "")}>
                    {String(v.authStatus ?? "-")}
                  </dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Token expiry</dt>
                  <dd className="truncate text-foreground">{v.tokenExpiryNearest != null ? String(v.tokenExpiryNearest).slice(0, 19) : "-"}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Heartbeat age</dt>
                  <dd className="text-foreground">{v.heartbeatAgeSeconds != null ? `${fmtInt(v.heartbeatAgeSeconds)}s` : "-"}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Rows</dt>
                  <dd className="text-foreground">
                    {fmtInt(v.connectedRows)}/{fmtInt(v.accountRows)} connected
                  </dd>
                </div>
              </dl>
              <div className="mt-2 flex flex-wrap gap-1">
                {vk === "ZERODHA" ? (
                  <>
                    <button
                      type="button"
                      disabled={!uid}
                      className="rounded border border-border bg-card px-2 py-0.5 text-[10px] font-medium text-foreground hover:bg-background disabled:opacity-40"
                      onClick={() => uid && runZerodha("/api/admin/brokers/orchestration/zerodha/test-session", { userId: uid })}
                    >
                      Refresh session
                    </button>
                    <button
                      type="button"
                      disabled={!uid}
                      className="rounded border border-border bg-card px-2 py-0.5 text-[10px] font-medium text-foreground hover:bg-background disabled:opacity-40"
                      onClick={() => uid && runZerodha("/api/admin/brokers/orchestration/zerodha/disconnect", { userId: uid })}
                    >
                      Disconnect
                    </button>
                    <button
                      type="button"
                      disabled={!uid}
                      className="rounded border border-border bg-card px-2 py-0.5 text-[10px] font-medium text-foreground hover:bg-background disabled:opacity-40"
                      onClick={() =>
                        uid && runZerodha("/api/admin/brokers/orchestration/feed-pause", { userId: uid, vendor: "ZERODHA", paused: true })
                      }
                    >
                      Pause feed
                    </button>
                    <button
                      type="button"
                      disabled={!uid}
                      className="rounded border border-border bg-card px-2 py-0.5 text-[10px] font-medium text-foreground hover:bg-background disabled:opacity-40"
                      onClick={() =>
                        uid && runZerodha("/api/admin/brokers/orchestration/feed-pause", { userId: uid, vendor: "ZERODHA", paused: false })
                      }
                    >
                      Resume feed
                    </button>
                  </>
                ) : (
                  <span className="text-[10px] text-muted-foreground">Orchestration API not wired for {vk}</span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </OpsPanel>
  );
}

export function MarketFreshnessPanel({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const m = asRecord(snapshot?.marketFreshness);
  const infra = asRecord(snapshot?.marketInfra);
  const life = asRecord(snapshot?.operationalLifecycle);
  const worst = asArray(m?.worstSymbols1m) ?? [];
  const ticks60 = infra?.ticksIngestedLast60sPlatformWs;
  const pathOk = life?.livePathOperational === true;
  const wsPacketsLabel =
    typeof ticks60 === "number" && Number.isFinite(ticks60)
      ? `${fmtInt(ticks60)} ticks / 60s (PLATFORM_ZERODHA_WS)`
      : pathOk
        ? "0 ticks / 60s (PLATFORM_ZERODHA_WS) - feed quiet or symbols idle"
        : "- (platform tape offline - no live tick plane)";

  return (
    <OpsPanel
      title="Market data health"
      subtitle="DB candle store freshness plus platform tick counters from operations snapshot (no invented rates)."
    >
      <div className="grid gap-4 lg:grid-cols-3">
        <div className="space-y-2 font-mono text-[11px] text-muted-foreground">
          <div className="flex justify-between gap-2">
            <span>Freshness status</span>
            <span className={`rounded border px-1.5 py-0.5 text-[10px] ${badgeClassForStatus(String(m?.status === "OK" ? "CONNECTED" : m?.status ?? "UNKNOWN"))}`}>
              {String(m?.status ?? "-")}
            </span>
          </div>
          <div className="flex justify-between gap-2">
            <span>1m lag (wall - max open)</span>
            <span className="text-foreground">{m?.latest1mLagSeconds != null ? `${fmtNum(m.latest1mLagSeconds, 0)}s` : "-"}</span>
          </div>
          <div className="flex justify-between gap-2">
            <span>1m candles / min (approx)</span>
            <span className="text-foreground">{fmtNum(m?.candles1mPerMinuteApprox, 2)}</span>
          </div>
          <div className="flex justify-between gap-2">
            <span>WS packets/sec</span>
            <span className="max-w-[14rem] truncate text-right text-foreground" title={String(wsPacketsLabel)}>
              {wsPacketsLabel}
            </span>
          </div>
          <div className="flex justify-between gap-2">
            <span>Distinct symbols</span>
            <span className="text-foreground">{fmtInt(m?.distinctSymbols)}</span>
          </div>
          <div className="flex justify-between gap-2">
            <span>Latest 1m open (UTC)</span>
            <span className="truncate text-foreground" title={String(infra?.latestCandleOpenTime1m ?? "")}>
              {infra?.latestCandleOpenTime1m != null ? String(infra.latestCandleOpenTime1m).slice(0, 19) : "-"}
            </span>
          </div>
          <div className="flex justify-between gap-2">
            <span>Latest 5m open (UTC)</span>
            <span className="truncate text-foreground" title={String(infra?.latestCandleOpenTime5m ?? "")}>
              {infra?.latestCandleOpenTime5m != null ? String(infra.latestCandleOpenTime5m).slice(0, 19) : "-"}
            </span>
          </div>
        </div>
        <div className="lg:col-span-2">
          <div className="text-xs font-medium text-foreground">Worst lagging symbols (1m store)</div>
          <div className="mt-2 max-h-48 overflow-auto rounded-lg border border-border">
            <table className="w-full border-collapse text-left font-mono text-[10px]">
              <thead className="sticky top-0 bg-card text-muted-foreground">
                <tr>
                  <th className="border-b border-border px-2 py-1">Symbol</th>
                  <th className="border-b border-border px-2 py-1">Latest open</th>
                  <th className="border-b border-border px-2 py-1">Heat</th>
                </tr>
              </thead>
              <tbody>
                {worst.length === 0 ? (
                  <tr>
                    <td colSpan={3} className="px-2 py-3 text-muted-foreground">
                      No rows (empty store or query blocked).
                    </td>
                  </tr>
                ) : (
                  worst.map((row, i) => {
                    const r = asRecord(row) ?? {};
                    const sym = String(r.symbol ?? "");
                    return (
                      <tr key={`${sym}-${i}`} className="border-b border-border/80">
                        <td className="px-2 py-1 text-foreground">{sym}</td>
                        <td className="px-2 py-1 text-muted-foreground">{String(r.latestOpenTime ?? "-").slice(0, 19)}</td>
                        <td className="px-2 py-1">
                          <div className="h-1.5 w-full overflow-hidden rounded bg-border">
                            <div
                              className="h-full bg-amber-500/80"
                              style={{ width: `${Math.min(100, 12 + i * 10)}%` }}
                              title="ordinal heat - not tick-accurate"
                            />
                          </div>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
          <p className="mt-2 text-[10px] text-muted-foreground">{String(m?.note ?? "")}</p>
        </div>
      </div>
    </OpsPanel>
  );
}

export function MarketIntelligenceGrid({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const mp = asRecord(snapshot?.marketPlane);
  const scan = asRecord(snapshot?.scannerTelemetry);
  const brokerLive = hasActiveBrokerMarketFeed(snapshot);
  const root = asRecord(snapshot?.brokerSessions);
  const vendors = asRecord(root?.vendors) ?? {};
  const freshness = String(mp?.freshnessStatus ?? "-");
  const worst = worstSymbolsFromSnapshot(snapshot);
  const running = typeof scan?.runningStrategyInstances === "number" ? scan.runningStrategyInstances : Number(scan?.runningStrategyInstances ?? 0);
  const sig60 = typeof scan?.signalsEmittedLast60m === "number" ? scan.signalsEmittedLast60m : Number(scan?.signalsEmittedLast60m ?? 0);

  return (
    <OpsPanel
      title="Market intelligence plane"
      subtitle="DB candle store + broker_accounts OAuth plane. Packet-level vendor taps are not in this build."
    >
      {!brokerLive ? (
        <div className="mb-4 rounded-lg border-2 border-orange-500/50 bg-orange-500/10 px-3 py-3 text-sm text-foreground">
          <div className="font-bold uppercase tracking-wide text-orange-950 dark:text-orange-100">Ingestion unavailable</div>
          <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
            No CONNECTED broker sessions - live ticks, scanner freshness, and tape-grade signals cannot be asserted. Use the broker
            control center to restore OAuth, then verify 1m lag and worst-symbol table below.
          </p>
        </div>
      ) : null}

      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <div className="rounded-lg border border-border bg-card px-3 py-2">
          <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">Freshness (plane)</div>
          <div className={`mt-1 inline-flex rounded border px-2 py-0.5 font-mono text-[11px] font-bold ${badgeClassForStatus(freshness === "OK" ? "CONNECTED" : freshness === "STALE" ? "STALE" : "DEGRADED")}`}>
            {freshness}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card px-3 py-2">
          <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">1m lag (wall - store)</div>
          <div className="mt-1 font-mono text-sm font-semibold text-foreground">
            {mp?.latest1mLagSeconds != null ? `${fmtNum(mp.latest1mLagSeconds, 0)}s` : "-"}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card px-3 py-2">
          <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">1m rows / min</div>
          <div className="mt-1 font-mono text-sm font-semibold text-foreground">{fmtNum(mp?.candles1mPerMinuteApprox, 2)}</div>
        </div>
        <div className="rounded-lg border border-border bg-card px-3 py-2">
          <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">Ops feed-paused (approx)</div>
          <div className="mt-1 font-mono text-sm font-semibold text-foreground">{fmtInt(mp?.adminFeedPausedAccountsApprox)}</div>
        </div>
      </div>

      <div className="mt-4">
        <div className="text-xs font-semibold text-foreground">A. Feed ownership (per broker)</div>
        <div className="mt-2 overflow-x-auto rounded-lg border border-border">
          <table className="w-full min-w-[640px] border-collapse text-left font-mono text-[11px]">
            <thead className="bg-muted/50 text-[10px] font-bold uppercase tracking-wide text-muted-foreground">
              <tr>
                <th className="border-b border-border px-2 py-2">Broker</th>
                <th className="border-b border-border px-2 py-2">Plane</th>
                <th className="border-b border-border px-2 py-2">WS (admin)</th>
                <th className="border-b border-border px-2 py-2">Token expiry</th>
                <th className="border-b border-border px-2 py-2">HB age</th>
                <th className="border-b border-border px-2 py-2">Connected</th>
                <th className="border-b border-border px-2 py-2">Paused</th>
              </tr>
            </thead>
            <tbody>
              {BROKER_CONTROL_CENTER_ORDER.map((vk) => {
                const v = asRecord(vendors[vk]) ?? {};
                const st = String(v.status ?? "-").toUpperCase();
                const ws = String(v.websocketStatus ?? "UNKNOWN").toUpperCase() === "UNKNOWN" ? "NOT_INSTRUMENTED" : String(v.websocketStatus ?? "-");
                return (
                  <tr key={vk} className="border-b border-border/80">
                    <td className="px-2 py-1.5 font-semibold text-foreground">{vendorDisplayName(vk)}</td>
                    <td className="px-2 py-1.5">
                      <span className={`rounded border px-1.5 py-0.5 text-[10px] font-bold ${badgeClassForStatus(st)}`}>{st}</span>
                    </td>
                    <td className="px-2 py-1.5 text-foreground">{ws}</td>
                    <td className="px-2 py-1.5 text-muted-foreground">{v.tokenExpiryNearest != null ? String(v.tokenExpiryNearest).slice(0, 19) : "-"}</td>
                    <td className="px-2 py-1.5 text-foreground">{v.heartbeatAgeSeconds != null ? `${fmtInt(v.heartbeatAgeSeconds)}s` : "-"}</td>
                    <td className="px-2 py-1.5 text-foreground">
                      {fmtInt(v.connectedRows)}/{fmtInt(v.accountRows)}
                    </td>
                    <td className="px-2 py-1.5 text-foreground">{fmtInt(v.adminFeedPausedAccounts)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <div>
          <div className="text-xs font-semibold text-foreground">B. Symbol freshness (1m store)</div>
          <div className="mt-2 max-h-56 overflow-auto rounded-lg border border-border">
            <table className="w-full border-collapse text-left font-mono text-[11px]">
              <thead className="sticky top-0 bg-card text-[10px] font-bold uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="border-b border-border px-2 py-1.5">Symbol</th>
                  <th className="border-b border-border px-2 py-1.5">Latest open</th>
                  <th className="border-b border-border px-2 py-1.5">Rank</th>
                </tr>
              </thead>
              <tbody>
                {!brokerLive ? (
                  <tr>
                    <td colSpan={3} className="px-2 py-4 text-sm text-muted-foreground">
                      No market telemetry - broker feed offline. Stale-symbol ranking requires live ingestion path.
                    </td>
                  </tr>
                ) : worst.length === 0 ? (
                  <tr>
                    <td colSpan={3} className="px-2 py-4 text-sm text-muted-foreground">
                      No worst-symbol rows returned (empty store or SQL probe returned zero).
                    </td>
                  </tr>
                ) : (
                  worst.slice(0, 40).map((row, i) => {
                    const r = asRecord(row) ?? {};
                    const sym = String(r.symbol ?? "");
                    return (
                      <tr key={`${sym}-${i}`} className="border-b border-border/80">
                        <td className="px-2 py-1 text-foreground">{sym}</td>
                        <td className="px-2 py-1 text-muted-foreground">{String(r.latestOpenTime ?? "-").slice(0, 19)}</td>
                        <td className="px-2 py-1 text-muted-foreground">#{i + 1}</td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </div>
        <div>
          <div className="text-xs font-semibold text-foreground">C. Aggregation + D. Scanner ownership</div>
          <dl className="mt-2 space-y-2 rounded-lg border border-border bg-card p-3 font-mono text-[11px] text-muted-foreground">
            <div className="flex justify-between gap-2">
              <dt>1m ingestion</dt>
              <dd className={`font-bold ${brokerLive ? "text-foreground" : "text-orange-700 dark:text-orange-200"}`}>
                {brokerLive ? (freshness === "OK" ? "NOMINAL" : String(freshness)) : "BLOCKED"}
              </dd>
            </div>
            <div className="flex justify-between gap-2">
              <dt>5m / aggregates</dt>
              <dd className="text-foreground">{brokerLive ? "Follows 1m plane (same probe)" : "BLOCKED"}</dd>
            </div>
            <div className="flex justify-between gap-2">
              <dt>Scans / engine</dt>
              <dd className="text-foreground">
                {brokerLive ? `${running} RUNNING  ·  ${sig60} sig / 60m` : "PAUSED (no broker feed)"}
              </dd>
            </div>
          </dl>
          <p className="mt-2 text-[10px] leading-relaxed text-muted-foreground">{String(mp?.note ?? "")}</p>
        </div>
      </div>
    </OpsPanel>
  );
}

export function OperationalHistoryStrip({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const h = asRecord(snapshot?.operationalHistory);
  const rows = asArray(h?.recent) ?? [];
  return (
    <OpsPanel title="Operational history" subtitle="Recent admin actions + orchestration audit (last 50).">
      <div className="max-h-40 overflow-auto rounded-lg border border-border font-mono text-[10px]">
        {rows.length === 0 ? (
          <div className="p-2 text-muted-foreground">No audit rows yet.</div>
        ) : (
          <ul className="divide-y divide-border">
            {rows.map((raw, i) => {
              const r = asRecord(raw) ?? {};
              const topic = String(r.topic ?? "");
              const at = String(r.createdAt ?? "").slice(0, 19);
              return (
                <li key={String(r.id ?? i)} className="flex flex-wrap items-baseline justify-between gap-2 px-2 py-1">
                  <span className="text-foreground">{topic}</span>
                  <span className="text-muted-foreground">{at}</span>
                </li>
              );
            })}
          </ul>
        )}
      </div>
      <p className="mt-1 text-[10px] text-muted-foreground">{String(h?.note ?? "")}</p>
    </OpsPanel>
  );
}

export function ExecutionTimelinePanel() {
  const [orderId, setOrderId] = useState("");
  const trimmed = orderId.trim();
  const valid = /^[0-9a-fA-F-]{36}$/.test(trimmed);
  const q = useQuery({
    queryKey: ["admin-exec-timeline", trimmed],
    queryFn: async () => {
      const res = await api.get(`/api/admin/operations/orders/${trimmed}/execution-timeline`);
      return (res.data?.data ?? []) as Array<Record<string, unknown>>;
    },
    enabled: valid,
    retry: 0,
  });

  return (
    <OpsPanel title="Execution timeline" subtitle="Append-only lifecycle from oms_execution_events (admin).">
      <div className="flex flex-wrap items-end gap-2">
        <label className="min-w-[12rem] flex-1 font-mono text-[10px] text-muted-foreground">
          OMS order UUID
          <input
            className="mt-1 w-full rounded border border-border bg-background px-2 py-1 font-mono text-[11px] text-foreground"
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
            placeholder="00000000-0000-0000-0000-000000000000"
            spellCheck={false}
          />
        </label>
      </div>
      {!valid ? <p className="mt-2 text-[10px] text-muted-foreground">Enter a valid order id to load the trace.</p> : null}
      {valid && q.isLoading ? <p className="mt-2 text-xs text-muted-foreground">Loading...</p> : null}
      {valid && q.isError ? <p className="mt-2 text-xs text-red-600 dark:text-red-400">Could not load timeline.</p> : null}
      {valid && q.data ? (
        <div className="mt-2 max-h-56 overflow-auto rounded-lg border border-border">
          <table className="w-full border-collapse text-left font-mono text-[10px]">
            <thead className="sticky top-0 bg-card text-muted-foreground">
              <tr>
                <th className="border-b border-border px-2 py-1">Seq</th>
                <th className="border-b border-border px-2 py-1">Type</th>
                <th className="border-b border-border px-2 py-1">At</th>
                <th className="border-b border-border px-2 py-1">Payload</th>
              </tr>
            </thead>
            <tbody>
              {q.data.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-2 py-2 text-muted-foreground">
                    No execution events for this order.
                  </td>
                </tr>
              ) : (
                q.data.map((row, i) => {
                  const seq = row.streamSequence;
                  const et = String(row.eventType ?? "");
                  const at = String(row.createdAt ?? "").slice(0, 19);
                  const pl = row.payload != null && typeof row.payload === "object" ? JSON.stringify(row.payload) : String(row.payload ?? "");
                  return (
                    <tr key={`${et}-${i}`} className="border-b border-border/80">
                      <td className="px-2 py-1 text-muted-foreground">{typeof seq === "number" ? seq : "-"}</td>
                      <td className="px-2 py-1 text-foreground">{et}</td>
                      <td className="px-2 py-1 text-muted-foreground">{at}</td>
                      <td className="max-w-[18rem] truncate px-2 py-1 text-muted-foreground" title={pl}>
                        {pl}
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      ) : null}
    </OpsPanel>
  );
}

export function StrategyScannerGrid({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const s = asRecord(snapshot?.scannerTelemetry);
  const rows = asArray(s?.strategyRows) ?? [];

  return (
    <OpsPanel title="Strategy scanner grid" subtitle="Catalog x RUNNING instances x persisted signals / 60m (DB-bound).">
      <dl className="mb-2 grid gap-1 font-mono text-[10px] text-muted-foreground sm:grid-cols-3 lg:grid-cols-6">
        <div className="flex justify-between gap-1 rounded border border-border bg-background/40 px-1.5 py-0.5">
          <dt>Eval (proc)</dt>
          <dd className="text-foreground">{fmtInt(s?.evaluationsTotal)}</dd>
        </div>
        <div className="flex justify-between gap-1 rounded border border-border bg-background/40 px-1.5 py-0.5">
          <dt>Sig from scan</dt>
          <dd className="text-foreground">{fmtInt(s?.signalsFromScannerTotal)}</dd>
        </div>
        <div className="flex justify-between gap-1 rounded border border-border bg-background/40 px-1.5 py-0.5">
          <dt>Scan fails</dt>
          <dd className="text-foreground">{fmtInt(s?.failuresTotal)}</dd>
        </div>
        <div className="flex justify-between gap-1 rounded border border-border bg-background/40 px-1.5 py-0.5">
          <dt>Last scan ms</dt>
          <dd className="text-foreground">{s?.lastScanDurationMs != null ? fmtNum(s.lastScanDurationMs, 1) : "-"}</dd>
        </div>
      </dl>
      <div className="max-h-[320px] overflow-auto rounded-lg border border-border">
        <table className="w-full border-collapse text-left font-mono text-[10px]">
          <thead className="sticky top-0 bg-card text-muted-foreground">
            <tr>
              <th className="border-b border-border px-2 py-1">Strategy</th>
              <th className="border-b border-border px-2 py-1">Cat.</th>
              <th className="border-b border-border px-2 py-1">RUNNING</th>
              <th className="border-b border-border px-2 py-1">Inst.</th>
              <th className="border-b border-border px-2 py-1">Sig/60m</th>
              <th className="border-b border-border px-2 py-1">Scan p50</th>
              <th className="border-b border-border px-2 py-1">Failures</th>
              <th className="border-b border-border px-2 py-1">Halted</th>
              <th className="border-b border-border px-2 py-1">Rejects</th>
              <th className="border-b border-border px-2 py-1">Backlog</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={10} className="px-2 py-3 text-muted-foreground">
                  No strategy_definitions rows.
                </td>
              </tr>
            ) : (
              rows.map((raw, i) => {
                const r = asRecord(raw) ?? {};
                const key = String(r.strategyKey ?? i);
                const running = Number(r.runningInstances ?? 0);
                const halted = running === 0 ? "IDLE" : "LIVE";
                return (
                  <tr key={key} className="border-b border-border/80">
                    <td className="px-2 py-1 text-foreground">{key}</td>
                    <td className="px-2 py-1 text-muted-foreground">{r.catalogEnabled === false ? "off" : "on"}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(running)}</td>
                    <td className="px-2 py-1 text-muted-foreground">{fmtInt(r.totalInstances)}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.signalsLast60m)}</td>
                    <td className="px-2 py-1 text-muted-foreground">{r.scanLatencyMsP50 != null ? fmtInt(r.scanLatencyMsP50) : "-"}</td>
                    <td className="px-2 py-1 text-muted-foreground">{r.scanFailuresApprox != null ? fmtInt(r.scanFailuresApprox) : "-"}</td>
                    <td className="px-2 py-1 text-muted-foreground">{halted}</td>
                    <td className="px-2 py-1 text-muted-foreground">{r.rejectedSignalsApprox != null ? fmtInt(r.rejectedSignalsApprox) : "-"}</td>
                    <td className="px-2 py-1 text-muted-foreground">{r.queueBacklogApprox != null ? fmtInt(r.queueBacklogApprox) : "-"}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-[10px] text-muted-foreground">{String(s?.note ?? "")}</p>
    </OpsPanel>
  );
}

export function SignalRoutingMonitor({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const d = asRecord(snapshot?.signalDistribution);
  const rows = asArray(d?.perTraderSignalDelivery) ?? [];
  const rt = asRecord(d?.routingTelemetry);

  return (
    <OpsPanel
      title="Signal routing monitor"
      subtitle="Signal engine -> OMS -> execution (in-process counters + DB tails; restart clears process totals)."
    >
      <div className="grid gap-2 font-mono text-[11px] text-muted-foreground sm:grid-cols-3 lg:grid-cols-4">
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Signals emitted (process)</div>
          <div className="text-foreground">{fmtInt(d?.signalsEmittedProcessTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Routed -&gt; OMS intents</div>
          <div className="text-foreground">{fmtInt(d?.signalsRoutedToOmsTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Rabbit dispatches</div>
          <div className="text-foreground">{fmtInt(d?.rabbitDispatchesTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Routing latency (sample avg)</div>
          <div className="text-foreground">{d?.routingLatencyMsP50 != null ? `${fmtNum(d.routingLatencyMsP50, 1)} ms` : "-"}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Execution dispatch failures</div>
          <div className="text-foreground">{fmtInt(d?.deliveryFailuresApprox)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Orders from signals (ACK proxy)</div>
          <div className="text-foreground">{fmtInt(d?.executionAcksApprox)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Execution retries</div>
          <div className="text-foreground">{fmtInt(d?.retryAttemptsTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Execution DLQ</div>
          <div className="text-foreground">{fmtInt(d?.dlqHandoffsTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Gate rejects (total)</div>
          <div className="text-foreground">{fmtInt(rt?.gateRejectedTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Risk rejects (total)</div>
          <div className="text-foreground">{fmtInt(rt?.riskRejectedTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Idempotent hits</div>
          <div className="text-foreground">{fmtInt(rt?.idempotentHitsTotal)}</div>
        </div>
      </div>
      <div className="mt-3 text-xs font-medium text-foreground">Per-trader routing (aggregated)</div>
      <div className="mt-1 max-h-52 overflow-auto rounded-lg border border-border">
        <table className="w-full border-collapse text-left font-mono text-[10px]">
          <thead className="sticky top-0 bg-card text-muted-foreground">
            <tr>
              <th className="border-b border-border px-2 py-1">User</th>
              <th className="border-b border-border px-2 py-1">Sig</th>
              <th className="border-b border-border px-2 py-1">OMS</th>
              <th className="border-b border-border px-2 py-1">Ord</th>
              <th className="border-b border-border px-2 py-1">Gate</th>
              <th className="border-b border-border px-2 py-1">Risk</th>
              <th className="border-b border-border px-2 py-1">Exec</th>
              <th className="border-b border-border px-2 py-1">Idemp</th>
              <th className="border-b border-border px-2 py-1">Flags</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={9} className="px-2 py-2 text-muted-foreground">
                  No per-trader routing samples yet.
                </td>
              </tr>
            ) : (
              rows.map((raw, i) => {
                const r = asRecord(raw) ?? {};
                const uid = String(r.userId ?? i);
                const flags = [
                  r.deliveredToOms === true ? "OMS" : null,
                  r.executedApprox === true ? "ORD" : null,
                  r.rejectedApprox === true ? "REJ" : null,
                ]
                  .filter(Boolean)
                  .join(" · ");
                return (
                  <tr key={uid} className="border-b border-border/80">
                    <td className="max-w-[7rem] truncate px-2 py-1 text-foreground" title={uid}>
                      {uid.slice(0, 8)}...
                    </td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.signalsPublished)}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.omsIntents)}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.ordersCreated)}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.gateRejects)}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.riskRejects)}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.executionFails)}</td>
                    <td className="px-2 py-1 text-muted-foreground">{fmtInt(r.idempotentHits)}</td>
                    <td className="max-w-[6rem] truncate px-2 py-1 text-muted-foreground" title={flags || "-"}>
                      {flags || "-"}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-[10px] text-muted-foreground">{String(rt?.note ?? "")}</p>
    </OpsPanel>
  );
}

export function SignalDistributionPanel({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const d = asRecord(snapshot?.signalDistribution);
  const sys = asRecord(snapshot?.system);
  const ws = typeof d?.websocketUsersApprox === "number" ? d.websocketUsersApprox : Number(d?.websocketUsersApprox ?? -1);

  return (
    <OpsPanel title="Signal distribution" subtitle="Persistence + coarse terminal fan-out proxy.">
      <dl className="grid gap-2 font-mono text-[11px] text-muted-foreground sm:grid-cols-2">
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Emitted (60m)</dt>
          <dd className="text-foreground">{fmtInt(d?.signalsEmittedLast60m)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Emitted (process)</dt>
          <dd className="text-foreground">{fmtInt(d?.signalsEmittedProcessTotal)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Live vs replay (60m)</dt>
          <dd className="text-foreground">
            {fmtInt(d?.signalsLiveLast60m)} / {fmtInt(d?.signalsReplayLast60m)}
          </dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Connected terminals (WS)</dt>
          <dd className="text-foreground">{ws < 0 ? "NOT_INSTRUMENTED" : fmtInt(ws)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Rabbit dispatches</dt>
          <dd className="text-foreground">{fmtInt(d?.rabbitDispatchesTotal)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Routed -&gt; OMS</dt>
          <dd className="text-foreground">{fmtInt(d?.signalsRoutedToOmsTotal)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Delivery failures</dt>
          <dd className="text-foreground">{d?.deliveryFailuresApprox == null ? "NOT_INSTRUMENTED" : fmtInt(d.deliveryFailuresApprox)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Execution acks</dt>
          <dd className="text-foreground">{d?.executionAcksApprox == null ? "NOT_INSTRUMENTED" : fmtInt(d.executionAcksApprox)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Retries / DLQ</dt>
          <dd className="text-foreground">
            {fmtInt(d?.retryAttemptsTotal)} / {fmtInt(d?.dlqHandoffsTotal)}
          </dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Routing latency (sample avg)</dt>
          <dd className="text-foreground">{d?.routingLatencyMsP50 == null ? "NOT_INSTRUMENTED" : `${fmtNum(d.routingLatencyMsP50, 0)} ms`}</dd>
        </div>
      </dl>
      <p className="mt-2 text-[10px] text-muted-foreground">{String(d?.routedTradersApproxNote ?? d?.note ?? "")}</p>
      <p className="mt-1 text-[10px] text-muted-foreground">System WS mirror: {fmtInt(sys?.websocketUsersApprox)}</p>
    </OpsPanel>
  );
}

export function OMSLatencyMonitor({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const o = asRecord(snapshot?.oms);
  const fails = asArray(o?.recentFailures) ?? [];

  return (
    <OpsPanel title="OMS execution monitor" subtitle="Order store + execution latency aggregate.">
      <dl className="grid gap-2 font-mono text-[11px] text-muted-foreground sm:grid-cols-2">
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Orders / sec (60s)</dt>
          <dd className="text-foreground">{fmtNum(o?.ordersPerSecApprox, 3)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Reject rate (all-time)</dt>
          <dd className="text-foreground">{fmtNum(o?.rejectRateApprox, 2)}%</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Ack latency avg</dt>
          <dd className="text-foreground">{o?.executionAvgLatencyMs != null ? `${fmtNum(o.executionAvgLatencyMs, 0)} ms` : "-"}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Failed + rejected (total)</dt>
          <dd className="text-foreground">
            {fmtInt(o?.ordersFailed)} / {fmtInt(o?.ordersRejected)}
          </dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Stuck (5m risk states)</dt>
          <dd className="text-foreground">{fmtInt(o?.stuckOrdersApprox)}</dd>
        </div>
        <div className="flex justify-between gap-2 rounded border border-border bg-background/50 px-2 py-1">
          <dt>Orders created last 60s</dt>
          <dd className="text-foreground">{fmtInt(o?.ordersCreatedLast60s)}</dd>
        </div>
      </dl>
      <div className="mt-3 text-xs font-medium text-foreground">Recent failures / rejects</div>
      <div className="mt-1 max-h-40 overflow-auto rounded-lg border border-border">
        <table className="w-full border-collapse text-left font-mono text-[10px]">
          <thead className="sticky top-0 bg-card text-muted-foreground">
            <tr>
              <th className="border-b border-border px-2 py-1">State</th>
              <th className="border-b border-border px-2 py-1">Sym</th>
              <th className="border-b border-border px-2 py-1">Mode</th>
              <th className="border-b border-border px-2 py-1">Updated</th>
            </tr>
          </thead>
          <tbody>
            {fails.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-2 py-2 text-muted-foreground">
                  No recent REJECTED/FAILED rows.
                </td>
              </tr>
            ) : (
              fails.map((raw, i) => {
                const r = asRecord(raw) ?? {};
                return (
                  <tr key={String(r.id ?? i)} className="border-b border-border/80">
                    <td className="px-2 py-1 text-foreground">{String(r.state ?? "")}</td>
                    <td className="px-2 py-1 text-muted-foreground">{String(r.symbol ?? "")}</td>
                    <td className="px-2 py-1 text-muted-foreground">{String(r.executionMode ?? "")}</td>
                    <td className="px-2 py-1 text-muted-foreground">{String(r.updatedAt ?? "").slice(0, 19)}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </OpsPanel>
  );
}

function mapReplayStatus(status: string, diagnosis: string | undefined): string {
  const s = status.toUpperCase();
  if (s === "FAILED") return String(diagnosis ?? "FAILED");
  if (s === "COMPLETED") return "COMPLETED";
  if (s === "CANCELLED") return "CANCELLED";
  if (s === "RUNNING" || s === "QUEUED") return s;
  return s;
}

export function ReplayOpsGrid({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const r = asRecord(snapshot?.replayInfra);
  const active = asArray(r?.activeReplayJobs) ?? [];
  const recent = asArray(r?.recentTerminalJobs) ?? [];

  return (
    <OpsPanel title="Replay operations center" subtitle="Job store + persisted replay diagnosis (terminal jobs).">
      <div className="grid gap-3 font-mono text-[11px] text-muted-foreground sm:grid-cols-3">
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Queued / running / failed</div>
          <div className="text-foreground">
            {fmtInt(r?.jobsQueued)}  ·  {fmtInt(r?.jobsRunning)}  ·  {fmtInt(r?.jobsFailed)}
          </div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Total jobs</div>
          <div className="text-foreground">{fmtInt(r?.jobsTotal)}</div>
        </div>
        <div className="rounded border border-border bg-background/50 px-2 py-1">
          <div>Completed</div>
          <div className="text-foreground">{fmtInt(r?.jobsCompleted)}</div>
        </div>
      </div>
      <div className="mt-3 text-xs font-medium text-foreground">Active replay jobs</div>
      <div className="mt-1 max-h-36 overflow-auto rounded-lg border border-border">
        <table className="w-full border-collapse text-left font-mono text-[10px]">
          <thead className="sticky top-0 bg-card text-muted-foreground">
            <tr>
              <th className="border-b border-border px-2 py-1">State</th>
              <th className="border-b border-border px-2 py-1">Diag</th>
              <th className="border-b border-border px-2 py-1">Progress</th>
              <th className="border-b border-border px-2 py-1">Updated</th>
            </tr>
          </thead>
          <tbody>
            {active.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-2 py-2 text-muted-foreground">
                  No queued/running jobs.
                </td>
              </tr>
            ) : (
              active.map((raw, i) => {
                const row = asRecord(raw) ?? {};
                const st = String(row.status ?? "");
                const diag = row.replayDiagnosis != null ? String(row.replayDiagnosis) : "";
                return (
                  <tr key={String(row.jobId ?? i)} className="border-b border-border/80">
                    <td className="px-2 py-1 text-foreground">{mapReplayStatus(st, diag)}</td>
                    <td className="max-w-[10rem] truncate px-2 py-1 text-muted-foreground" title={diag}>
                      {diag || "-"}
                    </td>
                    <td className="px-2 py-1 text-muted-foreground">
                      {fmtInt(row.progressPct)}%  ·  bars {fmtInt(row.processedBars)}/{fmtInt(row.totalBars)}
                    </td>
                    <td className="px-2 py-1 text-muted-foreground">{String(row.updatedAt ?? "").slice(0, 19)}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
      <div className="mt-3 text-xs font-medium text-foreground">Recent terminal jobs (diagnostics)</div>
      <div className="mt-1 max-h-48 overflow-auto rounded-lg border border-border">
        <table className="w-full border-collapse text-left font-mono text-[10px]">
          <thead className="sticky top-0 bg-card text-muted-foreground">
            <tr>
              <th className="border-b border-border px-2 py-1">State</th>
              <th className="border-b border-border px-2 py-1">Diagnosis</th>
              <th className="border-b border-border px-2 py-1">Candles</th>
              <th className="border-b border-border px-2 py-1">Signals</th>
              <th className="border-b border-border px-2 py-1">Dur</th>
            </tr>
          </thead>
          <tbody>
            {recent
              .filter((raw) => {
                const row = asRecord(raw) ?? {};
                const st = String(row.status ?? "").toUpperCase();
                return st === "COMPLETED" || st === "FAILED" || st === "CANCELLED";
              })
              .slice(0, 12)
              .map((raw, i) => {
                const row = asRecord(raw) ?? {};
                const st = String(row.status ?? "");
                const diag = String(row.replayDiagnosis ?? st);
                return (
                  <tr key={String(row.jobId ?? i)} className="border-b border-border/80">
                    <td className="px-2 py-1 text-foreground">{st}</td>
                    <td className="max-w-[14rem] truncate px-2 py-1 text-muted-foreground" title={diag}>
                      {diag}
                    </td>
                    <td className="px-2 py-1 text-muted-foreground">
                      {fmtInt(row.candlesProcessed)}/{fmtInt(row.candlesExpected)}
                    </td>
                    <td className="px-2 py-1 text-muted-foreground">{fmtInt(row.signalsEmitted)}</td>
                    <td className="px-2 py-1 text-muted-foreground">{row.durationMs != null ? `${fmtInt(row.durationMs)}ms` : "-"}</td>
                  </tr>
                );
              })}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-[10px] text-muted-foreground">{String(r?.note ?? "")}</p>
    </OpsPanel>
  );
}

export function IncidentFeed({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const rows = snapshot?.incidents ?? [];
  const [resolved, setResolved] = useState<Set<string>>(() => loadIds(LS_RESOLVED));
  const [muted, setMuted] = useState<Set<string>>(() => loadIds(LS_MUTED));
  const [acked, setAcked] = useState<Set<string>>(() => loadAck());

  useEffect(() => {
    setResolved(loadIds(LS_RESOLVED));
    setMuted(loadIds(LS_MUTED));
    setAcked(loadAck());
  }, [snapshot?.collectedAt]);

  const visible = useMemo(
    () =>
      rows
        .map((r) => asRecord(r) ?? {})
        .filter((r) => {
          const id = incidentId(r);
          return !muted.has(id) && !resolved.has(id);
        }),
    [rows, muted, resolved],
  );

  const ack = useCallback((row: Record<string, unknown>) => {
    const id = incidentId(row);
    setAcked((prev) => {
      const n = new Set(prev);
      n.add(id);
      saveAck(n);
      return n;
    });
  }, []);

  const mute = useCallback((row: Record<string, unknown>) => {
    const id = incidentId(row);
    setMuted((prev) => {
      const n = new Set(prev);
      n.add(id);
      saveIds(LS_MUTED, n);
      return n;
    });
  }, []);

  const resolve = useCallback((row: Record<string, unknown>) => {
    const id = incidentId(row);
    setResolved((prev) => {
      const n = new Set(prev);
      n.add(id);
      saveIds(LS_RESOLVED, n);
      return n;
    });
  }, []);

  return (
    <OpsPanel
      title="Incident command center"
      subtitle="Rule-derived incidents from live snapshot. Ack/mute/resolve are client-side until incident APIs exist."
    >
      {visible.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border px-3 py-6 text-center text-sm text-muted-foreground">No open incidents.</div>
      ) : (
        <ul className="space-y-2">
          {visible.map((row) => {
            const id = incidentId(row);
            const level = String(row.level ?? "info").toLowerCase();
            const border =
              level === "critical"
                ? "border-red-500/50 bg-red-500/5"
                : level === "warn"
                  ? "border-amber-500/50 bg-amber-500/5"
                  : "border-border bg-background/50";
            return (
              <li key={id} className={`rounded-lg border px-3 py-2 ${border}`}>
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-[10px] uppercase text-muted-foreground">{String(row.subsystem ?? "-")}</span>
                      <span className="rounded border border-border bg-card px-1.5 py-0.5 font-mono text-[10px] text-foreground">
                        {String(row.code ?? "")}
                      </span>
                      {acked.has(id) ? (
                        <span className="text-[10px] font-medium uppercase text-muted-foreground">acknowledged</span>
                      ) : null}
                    </div>
                    <div className="mt-1 text-sm font-semibold text-foreground">{String(row.title ?? "")}</div>
                    <div className="mt-0.5 font-mono text-[11px] text-muted-foreground">{String(row.detail ?? "")}</div>
                    <div className="mt-1 font-mono text-[10px] text-muted-foreground">{String(row.detectedAt ?? "").slice(0, 19)}</div>
                  </div>
                  <div className="flex flex-wrap gap-1">
                    <button
                      type="button"
                      className="rounded border border-border bg-card px-2 py-0.5 text-[10px] font-medium text-foreground hover:bg-background"
                      onClick={() => ack(row)}
                    >
                      Ack
                    </button>
                    <button
                      type="button"
                      className="rounded border border-border bg-card px-2 py-0.5 text-[10px] font-medium text-foreground hover:bg-background"
                      onClick={() => mute(row)}
                    >
                      Mute
                    </button>
                    <button
                      type="button"
                      className="rounded border border-border bg-card px-2 py-0.5 text-[10px] font-medium text-foreground hover:bg-background"
                      onClick={() => resolve(row)}
                    >
                      Resolve
                    </button>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </OpsPanel>
  );
}

export function TraderExecutionHealthGrid({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const t = asRecord(snapshot?.traderExecutionHealth);
  const rows = asArray(t?.traderRows) ?? [];

  return (
    <OpsPanel title="Trader execution health" subtitle="Recent traders - broker_sessions + OMS activity (DB).">
      <div className="max-h-[280px] overflow-auto rounded-lg border border-border">
        <table className="w-full border-collapse text-left font-mono text-[10px]">
          <thead className="sticky top-0 bg-card text-muted-foreground">
            <tr>
              <th className="border-b border-border px-2 py-1">User</th>
              <th className="border-b border-border px-2 py-1">LIVE ok</th>
              <th className="border-b border-border px-2 py-1">Brokers</th>
              <th className="border-b border-border px-2 py-1">Routing</th>
              <th className="border-b border-border px-2 py-1">Margin</th>
              <th className="border-b border-border px-2 py-1">Last OMS</th>
              <th className="border-b border-border px-2 py-1">OMS fail 24h</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-2 py-3 text-muted-foreground">
                  No auth_users rows.
                </td>
              </tr>
            ) : (
              rows.map((raw, i) => {
                const r = asRecord(raw) ?? {};
                const u = String(r.username ?? r.userId ?? i);
                return (
                  <tr key={String(r.userId ?? i)} className="border-b border-border/80">
                    <td className="px-2 py-1 text-foreground">{u}</td>
                    <td className="px-2 py-1 text-muted-foreground">{r.liveApproved === true ? "yes" : "no"}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.brokersConnected)}</td>
                    <td className="px-2 py-1 text-muted-foreground">{String(r.routingState ?? "")}</td>
                    <td className="px-2 py-1 text-muted-foreground">{String(r.marginState ?? "")}</td>
                    <td className="px-2 py-1 text-muted-foreground">{String(r.lastOrderAt ?? "").slice(0, 19) || "-"}</td>
                    <td className="px-2 py-1 text-foreground">{fmtInt(r.omsFailures24h)}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-[10px] text-muted-foreground">{String(t?.note ?? "")}</p>
    </OpsPanel>
  );
}

export function LiveSignalFeed({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const d = asRecord(snapshot?.signalDistribution);
  const sigs = asArray(d?.recentSignals) ?? [];
  const ws = typeof d?.websocketUsersApprox === "number" ? d.websocketUsersApprox : -1;

  return (
    <OpsPanel title="Live signal stream" subtitle="Tail of persisted strategy_signals (includes replay when backtest_run_id set).">
      <ul className="max-h-72 space-y-2 overflow-auto font-mono text-[11px]">
        {sigs.length === 0 ? (
          <li className="text-muted-foreground">No signals in tail.</li>
        ) : (
          sigs.map((raw, i) => {
            const s = asRecord(raw) ?? {};
            const t = String(s.createdAt ?? "").slice(11, 19);
            const strat = String(s.strategyName ?? "-");
            const side = String(s.signalType ?? "-");
            const sym = String(s.symbol ?? "-");
            const replay = Boolean(s.replay);
            const routed = ws < 0 ? "NOT_INSTRUMENTED" : `~${ws} terminals (WS)`;
            return (
              <li key={`${String(s.createdAt)}-${i}`} className="border-b border-border/60 pb-2">
                <div className="text-muted-foreground">{t}</div>
                <div className="text-foreground">
                  <span className="font-semibold">{strat}</span>{" "}
                  <span className="text-amber-200/90">{side}</span> <span className="text-foreground">{sym}</span>
                </div>
                <div className="text-muted-foreground">
                  routed -&gt; {routed}  -  {replay ? "REPLAY" : "LIVE/PAPER pipeline"}
                </div>
              </li>
            );
          })
        )}
      </ul>
    </OpsPanel>
  );
}

export function BackfillOperationsPanel({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const brokerLive = hasActiveBrokerMarketFeed(snapshot);
  const replay = asRecord(snapshot?.replayInfra);
  const jq = typeof replay?.jobsQueued === "number" ? replay.jobsQueued : Number(replay?.jobsQueued ?? 0);
  const jr = typeof replay?.jobsRunning === "number" ? replay.jobsRunning : Number(replay?.jobsRunning ?? 0);

  return (
    <OpsPanel title="Backfill control center" subtitle="Replay job queue from operations snapshot - historical job mutations are not on admin HTTP yet.">
      {!brokerLive ? (
        <div className="mb-3 rounded-lg border border-orange-500/45 bg-orange-500/10 px-3 py-2 text-xs text-foreground">
          <span className="font-semibold">Live coupling degraded  ·  </span>
          Without CONNECTED broker sessions, gap repair against live tape and freshness baselines cannot be validated from this
          console.
        </div>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-3">
        <div className="rounded-lg border border-border bg-card px-3 py-2">
          <div className="text-[10px] font-bold uppercase text-muted-foreground">Replay queued</div>
          <div className="mt-1 font-mono text-lg font-semibold text-foreground">{fmtInt(jq)}</div>
        </div>
        <div className="rounded-lg border border-border bg-card px-3 py-2">
          <div className="text-[10px] font-bold uppercase text-muted-foreground">Replay running</div>
          <div className="mt-1 font-mono text-lg font-semibold text-foreground">{fmtInt(jr)}</div>
        </div>
        <div className="rounded-lg border border-border bg-card px-3 py-2">
          <div className="text-[10px] font-bold uppercase text-muted-foreground">Broker feed</div>
          <div className={`mt-1 inline-flex rounded border px-2 py-0.5 font-mono text-[11px] font-bold ${badgeClassForStatus(brokerLive ? "CONNECTED" : "OFFLINE")}`}>
            {brokerLive ? "CONNECTED" : "OFFLINE"}
          </div>
        </div>
      </div>
      <p className="mt-4 text-[11px] leading-relaxed text-muted-foreground">
        Bulk backfill / aggregate rebuild / gap repair <span className="font-semibold text-foreground">admin mutations</span> are not
        exposed in this build. When APIs land, actions will bind here with progress + ETA. Until then, use DB tooling or worker
        consoles outside this UI.
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        <Link
          to="/admin/replay"
          className="rounded-lg border border-border bg-background px-3 py-1.5 text-[11px] font-semibold text-foreground hover:bg-muted"
        >
          Replay infrastructure
        </Link>
        <Link
          to="/admin/users"
          className="rounded-lg border border-border bg-background px-3 py-1.5 text-[11px] font-semibold text-foreground hover:bg-muted"
        >
          Trader roster
        </Link>
      </div>
    </OpsPanel>
  );
}

export function QueueDepthMonitor({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const sys = asRecord(snapshot?.system);
  const rabbit = asRecord(sys?.rabbitQueues) ?? {};
  const replay = asRecord(snapshot?.replayInfra);
  const names = ["strategy.signal.queue", "oms.order.queue", "execution.queue"] as const;

  return (
    <OpsPanel title="Queue telemetry" subtitle="Rabbit props + replay job backlog (orthogonal planes).">
      <div className="max-h-56 overflow-auto rounded-lg border border-border">
        <table className="w-full border-collapse text-left font-mono text-[10px]">
          <thead className="sticky top-0 bg-card text-muted-foreground">
            <tr>
              <th className="border-b border-border px-2 py-1">Queue</th>
              <th className="border-b border-border px-2 py-1">Depth</th>
              <th className="border-b border-border px-2 py-1">Rabbit props</th>
              <th className="border-b border-border px-2 py-1">Note</th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-b border-border/80">
              <td className="px-2 py-1 text-foreground">Replay job queue</td>
              <td className="px-2 py-1 text-muted-foreground">{fmtInt(replay?.jobsQueued)}</td>
              <td className="px-2 py-1 text-muted-foreground">running {fmtInt(replay?.jobsRunning)}</td>
              <td className="px-2 py-1 text-muted-foreground">DB counts, not AMQP</td>
            </tr>
            {names.map((name) => {
              const p = asRecord(rabbit[name]);
              const depth = queueDepth(p);
              return (
                <tr key={name} className="border-b border-border/80">
                  <td className="px-2 py-1 text-foreground">{name}</td>
                  <td className="px-2 py-1 text-muted-foreground">{depth >= 0 ? fmtInt(depth) : "-"}</td>
                  <td className="max-w-[14rem] truncate px-2 py-1 text-muted-foreground" title={queuePropsRow(p)}>
                    {queuePropsRow(p)}
                  </td>
                  <td className="px-2 py-1 text-muted-foreground">{depth > 2000 ? "SATURATED" : "-"}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </OpsPanel>
  );
}

export function ProjectionHealthPanel({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const m = asRecord(snapshot?.marketFreshness);
  const worst = asArray(m?.worstSymbols1m) ?? [];

  return (
    <OpsPanel
      title="Read model health"
      subtitle="Candle read path (marketdata_candles) - dedicated projection workers not split yet."
    >
      <dl className="space-y-2 font-mono text-[11px] text-muted-foreground">
        <div className="flex justify-between gap-2">
          <dt>Stale symbols (sample)</dt>
          <dd className="text-foreground">{worst.length} listed</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Projection lag (1m)</dt>
          <dd className="text-foreground">{m?.latest1mLagSeconds != null ? `${fmtNum(m.latest1mLagSeconds, 0)}s` : "-"}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Rebuild status</dt>
          <dd className="text-foreground">NOT_INSTRUMENTED</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Projection failures</dt>
          <dd className="text-foreground">NOT_INSTRUMENTED</dd>
        </div>
      </dl>
    </OpsPanel>
  );
}
