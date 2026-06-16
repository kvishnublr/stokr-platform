import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { cn } from "../../lib/utils";
import { badgeClassForStatus } from "../../components/admin/cockpit/opsTypes";
import { useUiThemeStore } from "../../state/uiTheme";
import { AdminPageShell, AdminPanel } from "../../components/admin/institutional/AdminDesignSystem";
import { openZerodhaOAuthPopup } from "../../lib/zerodhaOAuthPopup";
import { ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE } from "../../lib/zerodhaOAuthMessages";

type InfraPayload = {
  collectedAt?: string;
  plane?: string;
  note?: string;
  vendors?: Record<string, Record<string, unknown>>;
};

function asRecord(v: unknown): Record<string, unknown> | undefined {
  return v != null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

function fmt(v: unknown): string {
  if (v == null) return "-";
  if (typeof v === "boolean") return v ? "yes" : "no";
  return String(v);
}

function parseIsoInstant(value: unknown): Date | null {
  if (typeof value !== "string" || !value.trim()) return null;
  const dt = new Date(value);
  return Number.isNaN(dt.getTime()) ? null : dt;
}

function minutesUntil(value: unknown): number | null {
  const dt = parseIsoInstant(value);
  if (!dt) return null;
  return Math.round((dt.getTime() - Date.now()) / 60000);
}

function looksLikeZerodhaTokenAuthError(message: string): boolean {
  const m = (message || "").toLowerCase();
  return (
    m.includes("tokenexception") ||
    m.includes("incorrect `api_key`") ||
    m.includes("incorrect api_key") ||
    m.includes("access_token") ||
    m.includes("token invalid") ||
    m.includes("auth expired")
  );
}

function showReconnectPrompt(onOk: () => void) {
  toast.error("Zerodha session expired. Re-authenticate now?", {
    action: {
      label: "OK",
      onClick: onOk,
    },
    cancel: {
      label: "Cancel",
      onClick: () => {},
    },
    duration: 12000,
  });
}

const VENDOR_ORDER = ["ZERODHA", "UPSTOX", "ANGEL", "DHAN"] as const;

function displayVendor(v: string): string {
  switch (v) {
    case "ZERODHA":
      return "Zerodha";
    case "UPSTOX":
      return "Upstox";
    case "ANGEL":
      return "Angel One";
    case "DHAN":
      return "Dhan";
    default:
      return v;
  }
}

export function AdminBrokerInfrastructurePage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const qc = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const focusVendor = (searchParams.get("vendor") ?? "ZERODHA").toUpperCase();
  const [connectingOAuth, setConnectingOAuth] = useState(false);
  const oauthCancelRef = useRef<(() => void) | null>(null);

  const infra = useQuery({
    queryKey: ["admin-broker-infrastructure"],
    queryFn: async () => {
      const res = await api.get("/api/admin/broker-infrastructure");
      return res.data?.data as InfraPayload;
    },
    refetchInterval: 15_000,
  });

  useEffect(() => {
    return () => {
      oauthCancelRef.current?.();
      oauthCancelRef.current = null;
    };
  }, []);

  function finishPlatformFeedOAuth(status: "ok" | "error", reason?: string | null) {
    oauthCancelRef.current?.();
    oauthCancelRef.current = null;
    setConnectingOAuth(false);
    if (status === "ok") {
      toast.success("Platform Zerodha feed session established.");
      void qc.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
      void qc.invalidateQueries({ queryKey: ["admin-broker-infrastructure"] });
      void infra.refetch();
      return;
    }
    if (reason && looksLikeZerodhaTokenAuthError(reason)) {
      showReconnectPrompt(() => startPlatformFeedOAuth());
      return;
    }
    toast.error(reason ? `Platform feed OAuth failed (${reason})` : "Platform feed OAuth failed");
  }

  function startPlatformFeedOAuth() {
    if (connectingOAuth) return;
    connectZerodha.mutate();
  }

  useEffect(() => {
    const pf = searchParams.get("platform_feed");
    if (pf === "ok") {
      toast.success("Platform Zerodha feed session established.");
      const next = new URLSearchParams(searchParams);
      next.delete("platform_feed");
      setSearchParams(next, { replace: true });
      void qc.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
      void qc.invalidateQueries({ queryKey: ["admin-broker-infrastructure"] });
    } else if (pf === "error") {
      const reason = String(searchParams.get("reason") ?? "unknown");
      if (looksLikeZerodhaTokenAuthError(reason)) {
        showReconnectPrompt(() => startPlatformFeedOAuth());
      } else {
        toast.error(`Platform feed OAuth failed (${reason})`);
      }
      const next = new URLSearchParams(searchParams);
      next.delete("platform_feed");
      next.delete("reason");
      setSearchParams(next, { replace: true });
    }
  }, [searchParams, setSearchParams, qc]);

  const connectZerodha = useMutation({
    mutationFn: async () => {
      const res = await api.post<{ data?: { authorizeUrl?: string } }>("/api/admin/broker-infrastructure/ZERODHA/connect");
      return res.data?.data;
    },
    onMutate: () => {
      setConnectingOAuth(true);
    },
    onSuccess: (d) => {
      const url = d?.authorizeUrl;
      if (!url || typeof url !== "string") {
        setConnectingOAuth(false);
        toast.error("No authorizeUrl returned");
        return;
      }
      oauthCancelRef.current?.();
      const session = openZerodhaOAuthPopup({
        authorizeUrl: url,
        messageType: ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE,
        popupName: "stokr_platform_feed_oauth",
        onComplete: (result) => finishPlatformFeedOAuth(result.status, result.reason),
        onEarlyClose: () => {
          setConnectingOAuth(false);
          toast.message("OAuth window closed before platform feed linking finished.");
        },
      });
      oauthCancelRef.current = session.cancel;
      if (session.blocked) {
        oauthCancelRef.current = null;
        setConnectingOAuth(false);
        toast.error("Pop-up was blocked. Allow pop-ups for this site, then try Connect again.");
      }
    },
    onError: (e) => {
      setConnectingOAuth(false);
      toast.error(parseAxiosMessage(e));
    },
  });

  const refresh = useMutation({
    mutationFn: async (vendor: string) => {
      const res = await api.post(`/api/admin/broker-infrastructure/${vendor}/refresh`);
      return res.data?.data;
    },
    onSuccess: () => {
      toast.success("Kite profile refresh OK");
      void infra.refetch();
    },
    onError: (e) => {
      const msg = parseAxiosMessage(e);
      if (looksLikeZerodhaTokenAuthError(msg)) {
        showReconnectPrompt(() => connectZerodha.mutate());
        return;
      }
      toast.error(msg);
    },
  });

  const disconnect = useMutation({
    mutationFn: async (vendor: string) => {
      const res = await api.post(`/api/admin/broker-infrastructure/${vendor}/disconnect`);
      return res.data?.data;
    },
    onSuccess: () => {
      toast.message("Platform session cleared");
      void infra.refetch();
      void qc.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const pause = useMutation({
    mutationFn: async ({ vendor, paused }: { vendor: string; paused: boolean }) => {
      const res = await api.post(`/api/admin/broker-infrastructure/${vendor}/pause`, { paused });
      return res.data?.data;
    },
    onSuccess: () => {
      toast.message("Ingestion pause flag updated");
      void infra.refetch();
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const vendors = useMemo(() => {
    const m = asRecord(infra.data?.vendors) ?? {};
    return VENDOR_ORDER.map((k) => ({ key: k, row: asRecord(m[k]) ?? {} }));
  }, [infra.data]);

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Broker infrastructure"
      title="Mission-critical broker plane"
      subtitle="Platform-owned OAuth sessions, websocket heartbeat, order latency truth, and per-vendor health scores."
      alert={
        infra.isError ? (
          <div className="rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm">
            {parseAxiosMessage(infra.error)}
          </div>
        ) : undefined
      }
    >
    <div className="space-y-4 text-foreground">
      <AdminPanel isLight={isLight} title="Platform market feed" subtitle="Admin-owned ingestion — not trader execution accounts">
        <p className="text-sm leading-relaxed text-muted-foreground">
          <span className="font-semibold text-foreground">PLATFORM FEED</span> — OAuth rows in{" "}
          <code className="font-mono text-foreground">platform_broker_feed_sessions</code>. Trader execution lives under{" "}
          <Link to="/admin/users" className="font-medium text-primary underline-offset-2 hover:underline">
            trader roster
          </Link>
          .
        </p>
        {infra.data?.note ? <p className="mt-2 text-xs text-muted-foreground">{infra.data.note}</p> : null}
      </AdminPanel>

      <div className="grid gap-4 lg:grid-cols-2">
        {vendors.map(({ key, row }) => {
          const conn = String(row.connectionState ?? "DISCONNECTED").toUpperCase();
          const ws = String(row.websocketState ?? "CLOSED").toUpperCase();
          const configured = Boolean(row.configured);
          const hasRefreshToken = Boolean(row.hasRefreshToken);
          const expiryMins = minutesUntil(row.tokenExpiresAt);
          const warnReconnectSoon = configured && !hasRefreshToken && expiryMins != null && expiryMins <= 120;
          const warnReconnectNow = configured && !hasRefreshToken && expiryMins != null && expiryMins <= 30;
          const isFocus = key === focusVendor;
          return (
            <section
              key={key}
              className={cn(
                "flex flex-col rounded-xl border bg-card p-4 shadow-sm",
                isFocus ? "border-primary ring-1 ring-primary/30" : "border-border",
              )}
            >
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">Platform feed</div>
                  <h2 className="text-lg font-bold">{displayVendor(key)}</h2>
                </div>
                <div className="flex flex-col items-end gap-1">
                  <span className={`rounded-md border px-2 py-0.5 font-mono text-[11px] font-bold ${badgeClassForStatus(conn)}`}>
                    {conn.replace(/_/g, " ")}
                  </span>
                  <span className="text-[10px] font-mono text-muted-foreground">
                    WS <span className="text-foreground">{ws}</span>
                  </span>
                </div>
              </div>

              <dl className="mt-3 grid gap-1.5 font-mono text-[11px] text-muted-foreground sm:grid-cols-2">
                <div className="flex justify-between gap-2 sm:col-span-2">
                  <dt>Session configured</dt>
                  <dd className="text-foreground">{configured ? "yes" : "no"}</dd>
                </div>
                <div className="flex justify-between gap-2 sm:col-span-2">
                  <dt>Refresh token armed</dt>
                  <dd className={hasRefreshToken ? "text-emerald-500" : "text-amber-500"}>
                    {hasRefreshToken ? "yes" : "no"}
                  </dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Token expiry</dt>
                  <dd className="truncate text-foreground">{fmt(row.tokenExpiresAt)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Last sync</dt>
                  <dd className="truncate text-foreground">{fmt(row.lastSyncAt)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Last tick</dt>
                  <dd className="truncate text-foreground">{fmt(row.lastTickAt)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Packets/sec</dt>
                  <dd className="text-foreground">{fmt(row.packetsPerSec)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Ticks/sec</dt>
                  <dd className="text-foreground">{fmt(row.ticksPerSec)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Reconnect count</dt>
                  <dd className="text-foreground">{fmt(row.reconnectCount)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Subscriptions</dt>
                  <dd className="text-foreground">{fmt(row.subscriptionCount)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Feed lag (ms)</dt>
                  <dd className="text-foreground">{fmt(row.feedLagMs)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Ingestion paused (flag)</dt>
                  <dd className="text-foreground">{fmt(row.ingestionPaused)}</dd>
                </div>
                <div className="flex justify-between gap-2 sm:col-span-2">
                  <dt>Workers enforce pause</dt>
                  <dd className="text-foreground">{fmt(row.ingestionPauseEnforcedByWorkers)}</dd>
                </div>
                <div className="flex justify-between gap-2 sm:col-span-2">
                  <dt>Instrument sync</dt>
                  <dd className="truncate text-foreground">{fmt(row.instrumentSyncState)}</dd>
                </div>
              </dl>

              {row.detail ? <p className="mt-2 text-xs text-muted-foreground">{String(row.detail)}</p> : null}
              {warnReconnectSoon ? (
                <div
                  className={cn(
                    "mt-2 rounded-md border px-2 py-1 text-xs font-medium",
                    warnReconnectNow
                      ? "border-destructive/50 bg-destructive/10 text-destructive"
                      : "border-amber-500/40 bg-amber-500/10 text-amber-500",
                  )}
                >
                  {warnReconnectNow
                    ? `Platform feed token expires in ${expiryMins} min and refresh token is missing. Reconnect now to avoid feed drop.`
                    : `Platform feed token expires in ${expiryMins} min and refresh token is missing. Reconnect soon.`}
                </div>
              ) : null}

              <div className="mt-4 flex flex-wrap gap-2 border-t border-border pt-3">
                {key === "ZERODHA" ? (
                  <button
                    type="button"
                    disabled={connectingOAuth || connectZerodha.isPending}
                    className="rounded-lg border border-border bg-background px-3 py-1.5 text-[11px] font-bold text-foreground hover:bg-muted disabled:opacity-50"
                    onClick={() => startPlatformFeedOAuth()}
                  >
                    {connectingOAuth ? "Opening popup…" : "Connect platform feed (OAuth)"}
                  </button>
                ) : (
                  <span className="text-[11px] text-muted-foreground">Platform OAuth connect: Zerodha only in this build.</span>
                )}
                <button
                  type="button"
                  disabled={!configured || refresh.isPending}
                  className="rounded-lg border border-border bg-background px-3 py-1.5 text-[11px] font-semibold text-foreground hover:bg-muted disabled:opacity-40"
                  onClick={() => refresh.mutate(key)}
                >
                  Refresh token / ping Kite
                </button>
                <button
                  type="button"
                  disabled={!configured || pause.isPending}
                  className="rounded-lg border border-border bg-background px-3 py-1.5 text-[11px] font-semibold text-foreground hover:bg-muted disabled:opacity-40"
                  onClick={() => pause.mutate({ vendor: key, paused: true })}
                >
                  Pause ingestion
                </button>
                <button
                  type="button"
                  disabled={!configured || pause.isPending}
                  className="rounded-lg border border-border bg-background px-3 py-1.5 text-[11px] font-semibold text-foreground hover:bg-muted disabled:opacity-40"
                  onClick={() => pause.mutate({ vendor: key, paused: false })}
                >
                  Resume ingestion
                </button>
                <button
                  type="button"
                  disabled={!configured || disconnect.isPending}
                  className="rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-1.5 text-[11px] font-semibold text-destructive hover:bg-destructive/15 disabled:opacity-40"
                  onClick={() => disconnect.mutate(key)}
                >
                  Disconnect feed
                </button>
              </div>
            </section>
          );
        })}
      </div>

      <div className="rounded-lg border border-dashed border-border bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
        OAuth uses the same Kite-registered redirect URL as traders; completion is routed by OAuth state (platform vs trader). After
        success the popup closes automatically and this page refreshes.
      </div>
    </div>
    </AdminPageShell>
  );
}
