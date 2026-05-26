import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { motion } from "framer-motion";
import {
  Activity,
  ExternalLink,
  Landmark,
  MessageCircle,
  PlugZap,
  RefreshCw,
  ShieldCheck,
  Unplug,
  Wrench,
} from "lucide-react";
import { toast } from "sonner";
import {
  BROKER_STATUS_QUERY_KEY,
  fetchBrokerStatus,
  fetchZerodhaConnectUrl,
  postBrokerDisconnect,
  postBrokerTestConnection,
  postBrokerTestOrder,
  validateTestTradingsymbol,
  type BrokerTestConnectionDto,
  type BrokerTestOrderDto,
} from "../api/broker";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";
import { useSearchParams } from "react-router-dom";

const ZERODHA_OAUTH_MESSAGE = "stokr-zerodha-oauth";

/** www vs apex (or port) mismatch breaks strict origin checks; still require same host+protocol as this tab. */
function isTrustedZerodhaOauthMessageOrigin(origin: string): boolean {
  if (origin === window.location.origin) return true;
  try {
    const here = new URL(window.location.origin);
    const there = new URL(origin);
    if (here.protocol !== there.protocol) return false;
    if (here.port !== there.port) return false;
    const stripWww = (h: string) => h.toLowerCase().replace(/^www\./, "");
    return stripWww(here.hostname) === stripWww(there.hostname);
  } catch {
    return false;
  }
}

function TestOrderLegBlock({
  label,
  leg,
  isLight,
}: {
  label: string;
  leg: { orderId: string; status: string; message: string; filledQuantity: number | null; averagePrice: number | null };
  isLight: boolean;
}) {
  return (
    <div
      className={cn(
        "mt-2 rounded-md border px-2.5 py-2",
        isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-900/60",
      )}
    >
      <div className={cn("text-[10px] font-semibold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-500")}>
        {label}
      </div>
      <div className={cn("mt-1 space-y-0.5", isLight ? "text-neutral-600" : "text-neutral-400")}>
        <div>
          <span className="text-neutral-500">Order id:</span>{" "}
          <span className={cn("font-mono", isLight ? "text-neutral-900" : "text-neutral-200")}>{leg.orderId || "-"}</span>
        </div>
        <div>
          <span className="text-neutral-500">Status:</span> {leg.status}
        </div>
        {leg.filledQuantity != null ? (
          <div>
            <span className="text-neutral-500">Filled:</span> {leg.filledQuantity}
            {leg.averagePrice != null ? (
              <>
                {" "}
                <span className="text-neutral-500">@</span> ₹{leg.averagePrice.toFixed(2)}
              </>
            ) : null}
          </div>
        ) : null}
        <div>
          <span className="text-neutral-500">Note:</span> {leg.message}
        </div>
      </div>
    </div>
  );
}

export function BrokersPage() {
  const qc = useQueryClient();
  const [searchParams] = useSearchParams();
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const emailVerified = useSessionStore((s) => s.emailVerified);
  const [tgLink, setTgLink] = useState<string | null>(null);
  const [tradeOpen, setTradeOpen] = useState(false);
  const [symbol, setSymbol] = useState("INFY");
  const [qty, setQty] = useState(1);
  const [exchange, setExchange] = useState<"NSE" | "BSE">("NSE");
  const [variety, setVariety] = useState<"REGULAR" | "AMO">("REGULAR");
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [product, setProduct] = useState<"CNC" | "MIS">("CNC");
  const [lastTestOrder, setLastTestOrder] = useState<BrokerTestOrderDto | null>(null);
  const [connectingOAuth, setConnectingOAuth] = useState(false);
  const oauthPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const depositPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const oauthHandledRef = useRef(false);
  const oauthCleanupRef = useRef<(() => void) | null>(null);
  const oauthCloseGraceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const statusQuery = useQuery({
    queryKey: BROKER_STATUS_QUERY_KEY,
    queryFn: fetchBrokerStatus,
    retry: 1,
    staleTime: 15_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: true,
    refetchInterval: (q) => (q.state.error ? 10_000 : 30_000),
  });

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const z = params.get("zerodha");
    if (z !== "ok" && z !== "error") return;

    // Legacy callback URL (/brokers?zerodha=ok): forward to lightweight completion page.
    if (window.opener && !window.opener.closed) {
      const q = z === "ok" ? "ok" : "error";
      try {
        window.opener.postMessage({ type: ZERODHA_OAUTH_MESSAGE, status: q }, "*");
      } catch {
        /* ignore */
      }
      window.close();
      return;
    }
    window.location.replace(`/brokers/zerodha-complete?zerodha=${z === "ok" ? "ok" : "error"}`);
  }, []);

  function finishOauthFromParent(status: "ok" | "error") {
    oauthHandledRef.current = true;
    oauthCleanupRef.current?.();
    oauthCleanupRef.current = null;
    if (oauthPollRef.current) {
      clearInterval(oauthPollRef.current);
      oauthPollRef.current = null;
    }
    if (oauthCloseGraceRef.current) {
      clearTimeout(oauthCloseGraceRef.current);
      oauthCloseGraceRef.current = null;
    }
    setConnectingOAuth(false);
    if (status === "ok") {
      toast.success("Zerodha session linked");
      void qc.invalidateQueries({ queryKey: BROKER_STATUS_QUERY_KEY });
      void statusQuery.refetch();
    } else {
      toast.error("Zerodha linking failed - try again");
    }
  }

  useEffect(() => {
    const onStorage = (ev: StorageEvent) => {
      if (ev.key !== "stokr_zerodha_oauth_result" || !ev.newValue) return;
      try {
        const data = JSON.parse(ev.newValue) as { type?: string; status?: string };
        if (data.type !== ZERODHA_OAUTH_MESSAGE) return;
        finishOauthFromParent(data.status === "ok" ? "ok" : "error");
        localStorage.removeItem("stokr_zerodha_oauth_result");
      } catch {
        /* ignore */
      }
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  useEffect(() => {
    if (!connectingOAuth) return;
    const poll = setInterval(() => {
      void statusQuery.refetch().then((r) => {
        if (r.data?.connected && r.data?.tokenValid) {
          finishOauthFromParent("ok");
        }
      });
    }, 2000);
    const timeout = setTimeout(() => {
      if (!oauthHandledRef.current) {
        setConnectingOAuth(false);
        toast.message("Still connecting… If the popup is stuck, close it and click Refresh.");
      }
    }, 120_000);
    return () => {
      clearInterval(poll);
      clearTimeout(timeout);
    };
  }, [connectingOAuth]);

  useEffect(() => {
    return () => {
      oauthCleanupRef.current?.();
      oauthCleanupRef.current = null;
      if (oauthPollRef.current) {
        clearInterval(oauthPollRef.current);
        oauthPollRef.current = null;
      }
      if (oauthCloseGraceRef.current) {
        clearTimeout(oauthCloseGraceRef.current);
        oauthCloseGraceRef.current = null;
      }
      if (depositPollRef.current) {
        clearInterval(depositPollRef.current);
        depositPollRef.current = null;
      }
    };
  }, []);

  function openDepositPortalAndRefresh() {
    const popup = window.open(
      "https://kite.zerodha.com/funds",
      "stokr_deposit_funds",
      "popup=yes,width=1100,height=780,scrollbars=yes,resizable=yes,status=no,toolbar=no,menubar=no,location=yes",
    );
    if (!popup) {
      toast.error("Pop-up blocked. Allow pop-ups for this site, then try again.");
      return;
    }
    toast.message("Deposit window opened. Complete funding, then close it to refresh margin.");
    if (depositPollRef.current) clearInterval(depositPollRef.current);
    depositPollRef.current = setInterval(() => {
      if (!popup.closed) return;
      if (depositPollRef.current) {
        clearInterval(depositPollRef.current);
        depositPollRef.current = null;
      }
      toast.success("Refreshing broker margin snapshot...");
      void qc.invalidateQueries({ queryKey: BROKER_STATUS_QUERY_KEY });
      void statusQuery.refetch();
    }, 700);
  }

  async function requestTelegramLink() {
    try {
      const res = await api.post("/api/trader/integrations/telegram/verification-link");
      const payload = res.data?.data as { deepLink?: string } | undefined;
      const url = payload?.deepLink;
      if (url) {
        setTgLink(url);
        toast.message("Open Telegram to complete verification");
      }
    } catch (e: unknown) {
      toast.error(parseAxiosMessage(e));
    }
  }

  const testConnection = useMutation({
    mutationFn: postBrokerTestConnection,
    onSuccess: (data: BrokerTestConnectionDto) => {
      void qc.invalidateQueries({ queryKey: BROKER_STATUS_QUERY_KEY });
      if (data.ok) toast.success("Broker connection OK");
      else toast.error(data.message || "Test connection failed");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const disconnect = useMutation({
    mutationFn: postBrokerDisconnect,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: BROKER_STATUS_QUERY_KEY });
      setLastTestOrder(null);
      toast.success("Broker disconnected");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const testOrder = useMutation({
    mutationFn: () =>
      postBrokerTestOrder({
        variety,
        exchange,
        tradingsymbol: symbol.trim().toUpperCase(),
        side,
        quantity: qty,
        orderType: "MARKET",
        product,
      }),
    onSuccess: (data: BrokerTestOrderDto) => {
      setLastTestOrder(data);
      void qc.invalidateQueries({ queryKey: BROKER_STATUS_QUERY_KEY });
      if (data.dryRun) {
        toast.message("Dry run — round-trip simulated (no live orders)");
      } else if (data.squareOffStatus === "COMPLETED" || data.squareOffStatus === "SIMULATED") {
        toast.success(data.message || "Sample trade round-trip complete");
      } else if (data.orderId) {
        toast.message(data.message || `Entry placed: ${data.orderId}`);
      } else {
        toast.error(data.message || "Order rejected");
      }
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  async function openZerodhaConnect() {
    if (connectingOAuth) return;
    setConnectingOAuth(true);
    oauthHandledRef.current = false;
    oauthCleanupRef.current?.();
    oauthCleanupRef.current = null;
    if (oauthCloseGraceRef.current) {
      clearTimeout(oauthCloseGraceRef.current);
      oauthCloseGraceRef.current = null;
    }
    try {
      const url = await fetchZerodhaConnectUrl();
      // Do not add noopener: Zerodha completion loads this app in the popup and uses window.opener.postMessage.
      const features =
        "popup=yes,width=560,height=820,scrollbars=yes,resizable=yes,status=no,toolbar=no,menubar=no,location=yes";
      const popup = window.open(url, "stokr_zerodha_oauth", features);
      if (!popup) {
        toast.error("Pop-up was blocked. Allow pop-ups for this site, then try again.");
        setConnectingOAuth(false);
        return;
      }

      const onMessage = (ev: MessageEvent) => {
        if (!isTrustedZerodhaOauthMessageOrigin(ev.origin)) return;
        const data = ev.data as { type?: string; status?: string } | undefined;
        if (!data || data.type !== ZERODHA_OAUTH_MESSAGE) return;
        window.removeEventListener("message", onMessage);
        oauthCleanupRef.current = null;
        finishOauthFromParent(data.status === "ok" ? "ok" : "error");
      };
      window.addEventListener("message", onMessage);

      const tearDown = () => {
        window.removeEventListener("message", onMessage);
        if (oauthPollRef.current) {
          clearInterval(oauthPollRef.current);
          oauthPollRef.current = null;
        }
        if (oauthCloseGraceRef.current) {
          clearTimeout(oauthCloseGraceRef.current);
          oauthCloseGraceRef.current = null;
        }
      };
      oauthCleanupRef.current = tearDown;

      if (oauthPollRef.current) clearInterval(oauthPollRef.current);
      oauthPollRef.current = setInterval(() => {
        if (!popup.closed) return;

        if (oauthCloseGraceRef.current) return;

        if (oauthPollRef.current) {
          clearInterval(oauthPollRef.current);
          oauthPollRef.current = null;
        }

        // postMessage is queued; keep listener until grace elapses so we do not mis-report success as "closed early".
        oauthCloseGraceRef.current = setTimeout(() => {
          oauthCloseGraceRef.current = null;
          if (oauthHandledRef.current) {
            oauthCleanupRef.current = null;
            return;
          }
          tearDown();
          oauthCleanupRef.current = null;

          void (async () => {
            try {
              const next = await qc.fetchQuery({
                queryKey: BROKER_STATUS_QUERY_KEY,
                queryFn: fetchBrokerStatus,
              });
              if (next.connected && next.tokenValid) {
                setConnectingOAuth(false);
                toast.success("Zerodha session linked");
                void qc.invalidateQueries({ queryKey: BROKER_STATUS_QUERY_KEY });
                return;
              }
            } catch {
              /* ignore */
            }
            setConnectingOAuth(false);
            toast.message("Zerodha login window closed before linking finished.");
            void qc.invalidateQueries({ queryKey: BROKER_STATUS_QUERY_KEY });
          })();
        }, 750);
      }, 500);
    } catch (e: unknown) {
      toast.error(parseAxiosMessage(e));
      setConnectingOAuth(false);
    }
  }

  function closeTradeModal() {
    setTradeOpen(false);
    setLastTestOrder(null);
  }

  function openTradeModal() {
    setLastTestOrder(null);
    setTradeOpen(true);
  }

  function fireTestTrade() {
    const symErr = validateTestTradingsymbol(symbol);
    if (symErr) {
      toast.error(symErr);
      return;
    }
    const q = Math.floor(Number(qty));
    if (!Number.isFinite(q) || q < 1 || q > 5) {
      toast.error("Quantity must be an integer from 1 to 5.");
      return;
    }
    if (testOrder.isPending) return;
    testOrder.mutate();
  }

  const st = statusQuery.data;
  const loading = statusQuery.isLoading;
  const needsReconnect = Boolean(st?.connected && !st?.tokenValid);
  const sampleTradeDisabledReason =
    st?.testTradeDisabledReason ?? (!st?.connected ? "Connect Zerodha first" : null);
  const canUseSampleTrade = Boolean(st?.connected && st?.tokenValid && !sampleTradeDisabledReason);
  const marginHint =
    st?.marginSummary ??
    (st?.connected ? "Run test connection to refresh funds snapshot." : "-");
  const requestedDepositAmount = searchParams.get("depositAmount");

  const cardShell = isLight
    ? "rounded-2xl border border-neutral-200 bg-white p-5 shadow-sm sm:p-6"
    : "rounded-2xl border border-neutral-800 bg-neutral-900/50 p-5 sm:p-6";

  return (
    <div className="space-y-6 pb-8">
      <div>
        <div className={cn("text-xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
          Broker connections
        </div>
        <p className={cn("mt-2 max-w-2xl text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>
          Connect Zerodha via Kite OAuth (encrypted tokens server-side). Telegram verification is required before LIVE
          routing.
        </p>
      </div>

      {requestedDepositAmount ? (
        <div
          className={cn(
            "rounded-xl border px-4 py-3 text-sm",
            isLight ? "border-blue-200 bg-blue-50 text-blue-900" : "border-blue-500/35 bg-blue-500/10 text-blue-100",
          )}
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              Deposit request: <span className="font-mono font-semibold">? {requestedDepositAmount}</span>. Complete funding in your broker account portal, then run{" "}
              <span className="font-semibold">Test connection</span> to refresh margin snapshot.
            </div>
            <button
              type="button"
              onClick={openDepositPortalAndRefresh}
              className={cn(
                "rounded-lg border px-3 py-1.5 text-xs font-semibold",
                isLight
                  ? "border-blue-300 bg-white text-blue-900 hover:bg-blue-100"
                  : "border-blue-300/40 bg-blue-950/30 text-blue-100 hover:bg-blue-900/30",
              )}
            >
              Open deposit portal
            </button>
          </div>
        </div>
      ) : null}

      {needsReconnect ? (
        <div
          className={cn(
            "flex flex-col gap-3 rounded-xl border px-4 py-3 sm:flex-row sm:items-center sm:justify-between",
            isLight
              ? "border-amber-200 bg-amber-50 text-amber-950"
              : "border-amber-500/35 bg-amber-500/10",
          )}
          role="status"
        >
          <p className={cn("text-sm", isLight ? "text-amber-950/95" : "text-amber-100/95")}>
            Your saved Zerodha session is missing or expired. Reconnect to refresh your token; then run{" "}
            <span className={cn("font-medium", isLight ? "text-amber-950" : "text-white")}>Test connection</span> to verify
            profile and margins.
          </p>
          {emailVerified ? (
            <button
              type="button"
              disabled={connectingOAuth}
              onClick={() => void openZerodhaConnect()}
              className="shrink-0 rounded-lg bg-amber-500/90 px-3 py-2 text-xs font-semibold text-neutral-950 hover:bg-amber-400 disabled:opacity-50"
            >
              Reconnect Zerodha
            </button>
          ) : null}
        </div>
      ) : null}

      <motion.div
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        className="grid gap-6 lg:grid-cols-2"
      >
        <div className={cardShell}>
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex items-center gap-3">
              <div
                className={cn(
                  "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-sm font-bold",
                  isLight ? "bg-[#387ed1]/15 text-[#2563eb]" : "bg-[#387ed1]/20 text-[#9ec5fe]",
                )}
              >
                Z
              </div>
              <div className="min-w-0">
                <div
                  className={cn(
                    "flex items-center gap-2 text-sm font-semibold",
                    isLight ? "text-neutral-900" : "text-white",
                  )}
                >
                  <ShieldCheck className="h-4 w-4 shrink-0 text-emerald-500" />
                  Zerodha (Kite Connect)
                </div>
                <p className={cn("mt-1 break-words text-xs", isLight ? "text-neutral-600" : "text-neutral-500")}>
                  OAuth redirect; callback{" "}
                  <code
                    className={cn(
                      "rounded px-1 py-0.5 font-mono text-[11px]",
                      isLight ? "bg-neutral-100 text-neutral-800" : "text-neutral-300",
                    )}
                  >
                    /api/broker/zerodha/callback
                  </code>
                </p>
              </div>
            </div>
            <div className="flex shrink-0 flex-wrap items-center gap-2 sm:justify-end">
              {loading ? (
                <span className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>Loading...</span>
              ) : (
                <div
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold",
                    st?.connected && st?.tokenValid
                      ? isLight
                        ? "bg-emerald-100 text-emerald-800"
                        : "bg-emerald-500/15 text-emerald-200"
                      : st?.connected
                        ? isLight
                          ? "bg-amber-100 text-amber-900"
                          : "bg-amber-500/15 text-amber-100"
                        : isLight
                          ? "bg-neutral-100 text-neutral-600"
                          : "bg-neutral-800 text-neutral-400",
                  )}
                >
                  <Activity className="h-3 w-3" />
                  {st?.connected ? (st.tokenValid ? "Connected" : "Reconnect") : "Disconnected"}
                </div>
              )}
              <button
                type="button"
                title="Refresh broker status"
                disabled={statusQuery.isFetching || statusQuery.isPending}
                onClick={() => void statusQuery.refetch()}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold disabled:opacity-40",
                  isLight
                    ? "border-neutral-200 bg-white text-neutral-800 hover:bg-neutral-50"
                    : "border-neutral-600 text-neutral-200 hover:bg-neutral-800",
                )}
              >
                <RefreshCw className={`h-3.5 w-3.5 ${statusQuery.isFetching ? "animate-spin" : ""}`} />
                Refresh
              </button>
            </div>
          </div>

          <div
            className={cn(
              "mt-5 grid gap-3 text-xs sm:grid-cols-2",
              isLight ? "text-neutral-700" : "text-neutral-400",
            )}
          >
            <div className="min-w-0">
              <div className="text-[10px] uppercase tracking-wide text-neutral-500">Account</div>
              <div className={cn("mt-0.5 truncate", isLight ? "text-neutral-900" : "text-neutral-200")}>
                {st?.profileUserName ?? "-"}
              </div>
              <div className="truncate text-neutral-500">{st?.profileEmail ?? ""}</div>
            </div>
            <div className="min-w-0">
              <div className="text-[10px] uppercase tracking-wide text-neutral-500">Broker user id</div>
              <div
                className={cn(
                  "mt-0.5 break-all font-mono text-[11px]",
                  isLight ? "text-neutral-900" : "text-neutral-200",
                )}
              >
                {st?.accountId ?? "-"}
              </div>
            </div>
            <div className="flex min-w-0 items-start gap-2 sm:col-span-2">
              <Landmark className="mt-0.5 h-3.5 w-3.5 shrink-0 text-neutral-500" />
              <div className="min-w-0">
                <div className="text-[10px] uppercase tracking-wide text-neutral-500">Funds snapshot</div>
                <div className={cn("mt-0.5 break-words", isLight ? "text-neutral-800" : "text-neutral-200")}>
                  {marginHint}
                </div>
              </div>
            </div>
            <div className="sm:col-span-2">
              <div className="text-[10px] uppercase tracking-wide text-neutral-500">Last sync / token</div>
              <div className={cn("mt-0.5 flex flex-wrap items-baseline gap-x-1 break-words", isLight ? "text-neutral-800" : "text-neutral-200")}>
                <span>{formatInstant(st?.lastSyncAt ?? null)}</span>
                <span className="text-neutral-500"> · </span>
                <span
                  className={
                    st?.tokenValid
                      ? isLight
                        ? "text-emerald-700"
                        : "text-emerald-300/90"
                      : isLight
                        ? "text-amber-700"
                        : "text-amber-200/90"
                  }
                >
                  {st?.tokenValid
                    ? "token valid"
                    : st?.connected
                      ? "token invalid / expired"
                      : "no broker session linked"}
                </span>
                <span className="text-neutral-500"> ·  health {st?.health ?? "-"}</span>
              </div>
            </div>
          </div>

          {!emailVerified ? (
            <p className={cn("mt-4 text-xs", isLight ? "text-amber-800" : "text-amber-200/90")}>
              Verify email first (banner on every page).
            </p>
          ) : (
            <div className="mt-5 flex flex-wrap gap-2">
              <button
                type="button"
                disabled={connectingOAuth}
                onClick={() => void openZerodhaConnect()}
                className="inline-flex items-center gap-2 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-500 disabled:opacity-50"
              >
                {connectingOAuth ? "Redirecting..." : st?.connected ? "Reconnect Zerodha" : "Connect Zerodha"}
                <ExternalLink className="h-4 w-4" />
              </button>
              <button
                type="button"
                disabled={!st?.connected || !st?.tokenValid || testConnection.isPending}
                title={!st?.connected ? "Connect Zerodha first" : !st?.tokenValid ? "Reconnect Zerodha first" : undefined}
                onClick={() => testConnection.mutate()}
                className={cn(
                  "inline-flex items-center gap-2 rounded-lg border px-4 py-2 text-sm font-semibold disabled:opacity-40",
                  isLight
                    ? "border-neutral-200 bg-white text-neutral-900 hover:bg-neutral-50"
                    : "border-neutral-600 text-neutral-100 hover:bg-neutral-800",
                )}
              >
                <Wrench className="h-4 w-4" />
                Test connection
              </button>
              <button
                type="button"
                disabled={!canUseSampleTrade}
                title={sampleTradeDisabledReason ?? undefined}
                onClick={openTradeModal}
                className={cn(
                  "inline-flex items-center gap-2 rounded-lg border px-4 py-2 text-sm font-semibold disabled:opacity-40",
                  isLight
                    ? "border-sky-300 bg-sky-50 text-sky-900 hover:bg-sky-100"
                    : "border-sky-500/40 text-sky-100 hover:bg-sky-500/10",
                )}
              >
                <PlugZap className="h-4 w-4" />
                Sample trade
              </button>
              <button
                type="button"
                disabled={!st?.connected || disconnect.isPending}
                onClick={() => disconnect.mutate()}
                className={cn(
                  "inline-flex items-center gap-2 rounded-lg border px-4 py-2 text-sm font-semibold disabled:opacity-40",
                  isLight
                    ? "border-red-200 bg-white text-red-700 hover:bg-red-50"
                    : "border-red-500/30 text-red-200/90 hover:bg-red-500/10",
                )}
              >
                <Unplug className="h-4 w-4" />
                Disconnect
              </button>
            </div>
          )}

          <p className={cn("mt-3 text-xs", isLight ? "text-neutral-600" : "text-neutral-500")}>
            Sample trade places a tiny entry and auto square-off (qty 1–5).{" "}
            {st?.testOrderDryRun
              ? "Current mode is DRY RUN — round-trip is simulated only."
              : "Current mode places real Zerodha orders and closes the position automatically."}
            {sampleTradeDisabledReason ? ` ${sampleTradeDisabledReason}.` : ""}
          </p>

          {statusQuery.isError ? (
            <div className={cn("mt-3 flex flex-wrap items-center gap-2 text-xs", isLight ? "text-red-700" : "text-red-300/90")}>
              <span>{parseAxiosMessage(statusQuery.error)}</span>
              <button
                type="button"
                className={cn("font-semibold underline", isLight ? "text-red-800" : "text-red-200")}
                onClick={() => void statusQuery.refetch()}
              >
                Retry
              </button>
            </div>
          ) : null}
        </div>

        <div className={cardShell}>
          <div
            className={cn("flex items-center gap-2 text-sm font-semibold", isLight ? "text-neutral-900" : "text-white")}
          >
            <MessageCircle className="h-4 w-4 text-sky-500" />
            Telegram verification
          </div>
          <p className={cn("mt-2 text-xs", isLight ? "text-neutral-600" : "text-neutral-500")}>
            Set your @username under profile contact, then generate a deep link and tap{" "}
            <span className={cn("font-medium", isLight ? "text-neutral-900" : "text-neutral-300")}>Start</span> in the bot.
          </p>
          <button
            type="button"
            onClick={() => void requestTelegramLink()}
            className={cn(
              "mt-4 rounded-lg border px-4 py-2 text-sm font-semibold",
              isLight
                ? "border-sky-300 bg-sky-50 text-sky-900 hover:bg-sky-100"
                : "border-sky-500/40 text-sky-100 hover:bg-sky-500/10",
            )}
          >
            Get Telegram link
          </button>
          {tgLink ? (
            <a
              href={tgLink}
              target="_blank"
              rel="noreferrer"
              className={cn(
                "mt-3 block break-all text-xs underline",
                isLight ? "text-sky-700" : "text-sky-300",
              )}
            >
              {tgLink}
            </a>
          ) : null}
        </div>
      </motion.div>

      {tradeOpen ? (
        <div
          className={cn(
            "fixed inset-0 z-50 flex items-center justify-center overflow-y-auto p-4 py-8 backdrop-blur-sm",
            isLight ? "bg-black/40" : "bg-black/70",
          )}
          role="dialog"
          aria-modal="true"
          aria-labelledby="test-trade-title"
          onClick={(e) => {
            if (e.target === e.currentTarget) closeTradeModal();
          }}
        >
          <div
            className={cn(
              "my-auto w-full max-w-md rounded-2xl border p-5 shadow-xl sm:p-6",
              isLight ? "border-neutral-200 bg-white" : "border-neutral-700 bg-neutral-900",
            )}
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="test-trade-title" className={cn("text-lg font-semibold", isLight ? "text-neutral-900" : "text-white")}>
              Sample trade (MARKET)
            </h2>
            <p className={cn("mt-1 text-xs", isLight ? "text-neutral-600" : "text-neutral-500")}>
              Places a tiny MARKET entry (qty 1–5), waits briefly for fill, then auto square-off with the opposite
              side so you do not need the Intraday Terminal to close. Server uses Zerodha market protection for API
              MARKET orders. Live orders require{" "}
              <code className={cn(isLight ? "text-neutral-800" : "text-neutral-400")}>STOKR_ZERODHA_TEST_ORDER_ENABLED</code>{" "}
              and respect dry-run settings.
            </p>
            <div className="mt-4 max-h-[min(60vh,22rem)] space-y-3 overflow-y-auto pr-1">
              <label className={cn("block text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                Exchange
                <select
                  value={exchange}
                  onChange={(e) => setExchange(e.target.value as "NSE" | "BSE")}
                  className={cn(
                    "mt-1 w-full rounded-lg border px-3 py-2 text-sm",
                    isLight
                      ? "border-neutral-200 bg-white text-neutral-900"
                      : "border-neutral-700 bg-neutral-950 text-white",
                  )}
                >
                  <option value="NSE">NSE</option>
                  <option value="BSE">BSE</option>
                </select>
              </label>
              <label className={cn("block text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                Order variety
                <select
                  value={variety}
                  onChange={(e) => setVariety(e.target.value as "REGULAR" | "AMO")}
                  className={cn(
                    "mt-1 w-full rounded-lg border px-3 py-2 text-sm",
                    isLight
                      ? "border-neutral-200 bg-white text-neutral-900"
                      : "border-neutral-700 bg-neutral-950 text-white",
                  )}
                >
                  <option value="REGULAR">REGULAR (market session)</option>
                  <option value="AMO">AMO (after market)</option>
                </select>
              </label>
              <label className={cn("block text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                Symbol
                <input
                  value={symbol}
                  onChange={(e) => setSymbol(e.target.value)}
                  autoComplete="off"
                  className={cn(
                    "mt-1 w-full rounded-lg border px-3 py-2 text-sm",
                    isLight
                      ? "border-neutral-200 bg-white text-neutral-900"
                      : "border-neutral-700 bg-neutral-950 text-white",
                  )}
                />
              </label>
              <label className={cn("block text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                Quantity (1-5)
                <input
                  type="number"
                  min={1}
                  max={5}
                  step={1}
                  value={qty}
                  onChange={(e) => setQty(Number(e.target.value))}
                  className={cn(
                    "mt-1 w-full rounded-lg border px-3 py-2 text-sm",
                    isLight
                      ? "border-neutral-200 bg-white text-neutral-900"
                      : "border-neutral-700 bg-neutral-950 text-white",
                  )}
                />
              </label>
              <label className={cn("block text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                Product
                <select
                  value={product}
                  onChange={(e) => setProduct(e.target.value as "CNC" | "MIS")}
                  className={cn(
                    "mt-1 w-full rounded-lg border px-3 py-2 text-sm",
                    isLight
                      ? "border-neutral-200 bg-white text-neutral-900"
                      : "border-neutral-700 bg-neutral-950 text-white",
                  )}
                >
                  <option value="CNC">CNC (delivery)</option>
                  <option value="MIS">MIS (intraday)</option>
                </select>
              </label>
              <div className="flex gap-2">
                {(["BUY", "SELL"] as const).map((s) => (
                  <button
                    key={s}
                    type="button"
                    onClick={() => setSide(s)}
                    className={cn(
                      "flex-1 rounded-lg border py-2 text-sm font-semibold",
                      side === s
                        ? s === "BUY"
                          ? isLight
                            ? "border-emerald-500 bg-emerald-50 text-emerald-900"
                            : "border-emerald-500/60 bg-emerald-500/15 text-emerald-100"
                          : isLight
                            ? "border-red-400 bg-red-50 text-red-900"
                            : "border-red-500/50 bg-red-500/10 text-red-100"
                        : isLight
                          ? "border-neutral-200 text-neutral-600 hover:bg-neutral-50"
                          : "border-neutral-700 text-neutral-400 hover:bg-neutral-800",
                    )}
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
            {lastTestOrder ? (
              <div
                className={cn(
                  "mt-4 rounded-lg border p-3 text-xs",
                  isLight ? "border-neutral-200 bg-neutral-50" : "border-neutral-700 bg-neutral-950/80",
                )}
              >
                <div className={cn("font-semibold", isLight ? "text-neutral-900" : "text-neutral-200")}>
                  Round-trip result
                </div>
                <div className={cn("mt-1 space-y-1", isLight ? "text-neutral-600" : "text-neutral-400")}>
                  <div>
                    <span className="text-neutral-500">Summary:</span> {lastTestOrder.message}
                  </div>
                  {lastTestOrder.squareOffStatus ? (
                    <div>
                      <span className="text-neutral-500">Square-off:</span>{" "}
                      <span
                        className={cn(
                          "font-medium",
                          lastTestOrder.squareOffStatus === "COMPLETED" ||
                            lastTestOrder.squareOffStatus === "SIMULATED"
                            ? isLight
                              ? "text-emerald-700"
                              : "text-emerald-300"
                            : isLight
                              ? "text-amber-800"
                              : "text-amber-200",
                        )}
                      >
                        {lastTestOrder.squareOffStatus}
                      </span>
                    </div>
                  ) : null}
                  {lastTestOrder.pnl != null ? (
                    <div>
                      <span className="text-neutral-500">Est. PnL:</span>{" "}
                      <span className={cn("font-mono font-medium", isLight ? "text-neutral-900" : "text-neutral-200")}>
                        ₹ {lastTestOrder.pnl.toFixed(2)}
                      </span>
                    </div>
                  ) : null}
                  {lastTestOrder.dryRun ? (
                    <div>
                      <span className="text-neutral-500">Mode:</span> dry run (simulated)
                    </div>
                  ) : null}
                </div>
                {lastTestOrder.entry ? (
                  <TestOrderLegBlock label="Entry" leg={lastTestOrder.entry} isLight={isLight} />
                ) : (
                  <div className={cn("mt-2", isLight ? "text-neutral-600" : "text-neutral-400")}>
                    <span className="text-neutral-500">Entry id:</span>{" "}
                    <span className={cn("font-mono", isLight ? "text-neutral-900" : "text-neutral-200")}>
                      {lastTestOrder.orderId || "-"}
                    </span>
                    <span className="text-neutral-500"> · </span>
                    <span>{lastTestOrder.status}</span>
                  </div>
                )}
                {lastTestOrder.exit ? (
                  <TestOrderLegBlock label="Exit" leg={lastTestOrder.exit} isLight={isLight} />
                ) : lastTestOrder.squareOffStatus === "ENTRY_NOT_FILLED" ? (
                  <p className={cn("mt-2 text-[11px]", isLight ? "text-amber-800" : "text-amber-200/90")}>
                    Entry did not fill in time — no exit order sent.
                  </p>
                ) : null}
              </div>
            ) : null}
            <div
              className={cn(
                "mt-5 flex flex-wrap justify-end gap-2 border-t pt-4",
                isLight ? "border-neutral-200" : "border-neutral-800",
              )}
            >
              <button
                type="button"
                onClick={closeTradeModal}
                className={cn(
                  "rounded-lg px-4 py-2 text-sm",
                  isLight ? "text-neutral-700 hover:bg-neutral-100" : "text-neutral-300 hover:bg-neutral-800",
                )}
              >
                Close
              </button>
              <button
                type="button"
                disabled={testOrder.isPending}
                onClick={fireTestTrade}
                className={cn(
                  "rounded-lg bg-sky-600 px-4 py-2 text-sm font-semibold text-white hover:bg-sky-500 disabled:opacity-50",
                  isLight && "shadow-sm",
                )}
              >
                {testOrder.isPending ? "Running round-trip…" : "Run sample trade"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function formatInstant(iso: string | null): string {
  if (!iso) return "-";
  try {
    return new Date(iso).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", hour12: false, day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
  } catch {
    return iso;
  }
}


