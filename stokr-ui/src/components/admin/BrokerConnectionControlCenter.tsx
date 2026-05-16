import { useCallback, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { BROKER_CONTROL_CENTER_ORDER, hasActiveBrokerMarketFeed, vendorDisplayName } from "./adminReadinessModel";
import { asRecord, badgeClassForStatus, fmtInt, type OpsSnapshot } from "./cockpit/opsTypes";

function mapVendorOperationalStatus(v: Record<string, unknown>): string {
  const status = String(v.status ?? "DISCONNECTED").toUpperCase();
  const auth = String(v.authStatus ?? "");
  if (auth.includes("TOKENS_EXPIRED")) return "AUTH_EXPIRED";
  if (status === "CONNECTED") {
    const hb = typeof v.heartbeatAgeSeconds === "number" ? v.heartbeatAgeSeconds : Number(v.heartbeatAgeSeconds ?? NaN);
    if (Number.isFinite(hb) && hb > 180) return "DEGRADED";
    return "CONNECTED";
  }
  if (status === "DEGRADED") return "DEGRADED";
  return status === "UNKNOWN" ? "DISCONNECTED" : status;
}

function wsDisplay(v: Record<string, unknown>): string {
  const w = String(v.websocketStatus ?? "UNKNOWN").toUpperCase();
  if (w === "UNKNOWN") return "NOT_INSTRUMENTED";
  return w;
}

export function BrokerConnectionControlCenter({
  snapshot,
  dense = false,
}: {
  snapshot: OpsSnapshot | undefined;
  dense?: boolean;
}) {
  const qc = useQueryClient();
  const root = asRecord(snapshot?.brokerSessions);
  const vendors = asRecord(root?.vendors) ?? {};
  const marketLive = hasActiveBrokerMarketFeed(snapshot);
  const [userPick, setUserPick] = useState<Record<string, string>>({});

  const invalidate = useCallback(() => {
    void qc.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
  }, [qc]);

  const runZerodha = useCallback(
    async (path: string, body: Record<string, unknown>) => {
      try {
        const res = await api.post(path, body);
        toast.success(String((res.data as { data?: { message?: string } })?.data?.message ?? "OK"));
        invalidate();
      } catch (e) {
        toast.error(parseAxiosMessage(e));
      }
    },
    [invalidate],
  );

  return (
    <section className="rounded-xl border border-border bg-card shadow-sm">
      <div className="flex flex-col gap-2 border-b border-border px-4 py-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-sm font-bold uppercase tracking-wide text-foreground">Broker connection control center</h2>
          <p className="mt-1 max-w-3xl text-xs leading-relaxed text-muted-foreground">
            <span className="font-semibold text-foreground">TRADER EXECUTION</span> telemetry below is aggregated from{" "}
            <span className="font-mono text-foreground">broker_accounts</span> (per-trader OAuth). For{" "}
            <span className="font-semibold text-foreground">PLATFORM MARKET FEED</span> ownership (admin OAuth), open{" "}
            <Link to="/admin/broker-infrastructure" className="font-medium text-primary underline-offset-2 hover:underline">
              Broker infrastructure
            </Link>
            . Vendor WS counters here remain{" "}
            <span className="font-medium text-foreground">not instrumented in-process</span> - heartbeat uses{" "}
            <span className="font-mono">last_sync_at</span>.
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          <Link
            to="/admin/broker-infrastructure"
            className="rounded-lg border border-primary/40 bg-primary/10 px-3 py-1.5 text-center text-[11px] font-bold text-foreground hover:bg-primary/15"
          >
            Platform feed console
          </Link>
          <Link
            to="/admin/users"
            className="rounded-lg border border-border bg-background px-3 py-1.5 text-center text-[11px] font-semibold text-foreground hover:bg-muted"
          >
            Trader roster
          </Link>
          <Link
            to="/admin/controls"
            className="rounded-lg border border-border bg-background px-3 py-1.5 text-center text-[11px] font-semibold text-foreground hover:bg-muted"
          >
            Controls
          </Link>
        </div>
      </div>

      {!marketLive ? (
        <div className="border-b border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-foreground dark:border-amber-400/35 dark:bg-amber-500/15">
          <span className="font-semibold">Market intelligence offline  ·  </span>
          No CONNECTED broker sessions. Live ingestion, scanners, replay freshness coupling, and live-tape signals are unavailable
          until OAuth is healthy.
        </div>
      ) : null}

      <div className={`grid gap-3 p-4 ${dense ? "sm:grid-cols-2" : "sm:grid-cols-2 xl:grid-cols-4"}`}>
        {BROKER_CONTROL_CENTER_ORDER.map((vk) => {
          const v = asRecord(vendors[vk]) ?? {};
          const opSt = mapVendorOperationalStatus(v);
          const samples = (Array.isArray(v.sampleUserIds) ? v.sampleUserIds : []).map(String);
          const uid = userPick[vk] ?? samples[0] ?? "";
          const label = vendorDisplayName(vk);
          const packets = v.feedLagInstrumented === false ? "-" : fmtInt(v.packetsPerSecondApprox);
          const lag = v.feedLagInstrumented === false ? "-" : v.feedLagSeconds != null ? `${fmtInt(v.feedLagSeconds)}s` : "-";

          return (
            <div
              key={vk}
              className={`flex flex-col rounded-lg border border-border bg-background ${dense ? "p-3" : "p-4"} shadow-[inset_0_1px_0_0_hsl(var(--border)/0.5)]`}
            >
              <div className="flex items-start justify-between gap-2">
                <span className="text-sm font-semibold text-foreground">{label}</span>
                <span className={`shrink-0 rounded-md border px-2 py-0.5 font-mono text-[11px] font-bold ${badgeClassForStatus(opSt)}`}>
                  {opSt.replace(/_/g, " ")}
                </span>
              </div>

              <dl className="mt-3 space-y-1.5 font-mono text-[11px] leading-snug">
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>WebSocket (admin)</dt>
                  <dd className="text-right text-foreground">{wsDisplay(v)}</dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Token expiry (nearest)</dt>
                  <dd className="truncate text-right text-foreground" title={String(v.tokenExpiryNearest ?? "")}>
                    {v.tokenExpiryNearest != null ? String(v.tokenExpiryNearest).slice(0, 19) : "-"}
                  </dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Heartbeat age</dt>
                  <dd className="text-foreground">{v.heartbeatAgeSeconds != null ? `${fmtInt(v.heartbeatAgeSeconds)}s` : "-"}</dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Reconnect count</dt>
                  <dd className="text-foreground">
                    {v.reconnectCountInstrumented === true && v.reconnectCount != null ? fmtInt(v.reconnectCount) : "-"}
                  </dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Feed lag</dt>
                  <dd className="text-foreground">{lag}</dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Packets/sec</dt>
                  <dd className="text-foreground">{packets}</dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Sessions</dt>
                  <dd className="text-foreground">
                    {fmtInt(v.connectedRows)}/{fmtInt(v.accountRows)} connected
                  </dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Feeds paused (ops)</dt>
                  <dd className="text-foreground">{fmtInt(v.adminFeedPausedAccounts)}</dd>
                </div>
                <div className="flex justify-between gap-2 text-muted-foreground">
                  <dt>Last sync (max)</dt>
                  <dd className="truncate text-right text-foreground" title={String(v.lastSyncAtMax ?? "")}>
                    {v.lastSyncAtMax != null ? String(v.lastSyncAtMax).slice(0, 19) : "-"}
                  </dd>
                </div>
              </dl>

              <div className="mt-3 border-t border-border pt-3">
                <label className="block text-[10px] font-bold uppercase tracking-wide text-muted-foreground">Orchestration target</label>
                <select
                  className="mt-1 w-full rounded-md border border-border bg-card px-2 py-1.5 font-mono text-[11px] text-foreground"
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
                <div className="mt-2 flex flex-wrap gap-1.5">
                  <Link
                    to={`/admin/broker-infrastructure?vendor=${encodeURIComponent(vk)}`}
                    className="rounded-md border border-primary/35 bg-primary/10 px-2 py-1 text-[10px] font-bold text-foreground hover:bg-primary/15"
                  >
                    Platform feed connect
                  </Link>
                  {vk === "ZERODHA" ? (
                    <>
                      <button
                        type="button"
                        disabled={!uid}
                        className="rounded-md border border-border bg-card px-2 py-1 text-[10px] font-semibold text-foreground hover:bg-background disabled:opacity-40"
                        onClick={() => uid && runZerodha("/api/admin/brokers/orchestration/zerodha/test-session", { userId: uid })}
                      >
                        Refresh session
                      </button>
                      <button
                        type="button"
                        disabled={!uid}
                        className="rounded-md border border-border bg-card px-2 py-1 text-[10px] font-semibold text-foreground hover:bg-background disabled:opacity-40"
                        onClick={() => uid && runZerodha("/api/admin/brokers/orchestration/zerodha/disconnect", { userId: uid })}
                      >
                        Disconnect
                      </button>
                      <button
                        type="button"
                        disabled={!uid}
                        className="rounded-md border border-border bg-card px-2 py-1 text-[10px] font-semibold text-foreground hover:bg-background disabled:opacity-40"
                        onClick={() =>
                          uid &&
                          runZerodha("/api/admin/brokers/orchestration/feed-pause", {
                            userId: uid,
                            vendor: "ZERODHA",
                            paused: true,
                          })
                        }
                      >
                        Pause feed
                      </button>
                      <button
                        type="button"
                        disabled={!uid}
                        className="rounded-md border border-border bg-card px-2 py-1 text-[10px] font-semibold text-foreground hover:bg-background disabled:opacity-40"
                        onClick={() =>
                          uid &&
                          runZerodha("/api/admin/brokers/orchestration/feed-pause", {
                            userId: uid,
                            vendor: "ZERODHA",
                            paused: false,
                          })
                        }
                      >
                        Resume feed
                      </button>
                    </>
                  ) : (
                    <span className="self-center text-[10px] text-muted-foreground">Admin orchestration API: Zerodha only in this build.</span>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
