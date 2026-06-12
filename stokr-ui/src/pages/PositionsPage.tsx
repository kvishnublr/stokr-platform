import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { api } from "../api/client";
import { TRADER_EXECUTION_MODE_QUERY_KEY, fetchTraderExecutionMode } from "../lib/traderExecutionMode";
import {
  formatBrokerSyncAge,
  brokerPositionPollMs,
  isBrokerSyncPulseLive,
  useBrokerPositionSync,
} from "../lib/hooks/useBrokerPositionSync";
import { usePositionExit } from "../lib/hooks/usePositionExit";
import { useSessionStore } from "../state/session";
import {
  computePositionNotional,
  formatInr,
  formatPnlDisplay,
  parseMoney,
  pnlToneClass,
  filterBrokerMirrorPositions,
  isBrokerSessionLive,
  resolveAccountPnl,
  resolvePositionParityBadge,
  resolvePositionQuantitySource,
} from "../lib/moneyUtils";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import {
  AnimatedKpiCard,
  EmptyState,
  LivePositionsCommandTable,
  PnlCommandRail,
  PnlSourceBadge,
  PremiumPanel,
  TraderPageShell,
  type CommandPositionRow,
  fadeUp,
} from "../components/trader/TraderPremium";
import { Download, ExternalLink, RefreshCw, TrendingUp } from "lucide-react";

type Exposure = {
  bySymbol: {
    symbol: string;
    quantity: string;
    exposureNotional: string;
    omsQuantity?: string | null;
    quantitySource?: string | null;
    parityState?: string | null;
  }[];
  byBrokerNotional: { brokerVendor: string; tradedNotionalApprox: string }[];
};

type Workstation = {
  accountSummary?: Record<string, unknown>;
  brokerTruth?: Record<string, unknown>;
  openPositions?: Array<Record<string, unknown>>;
};

type PositionRow = {
  symbol: string;
  side: string;
  qty: number;
  brokerQty: number | null;
  avgPrice: number | null;
  ltp: number | null;
  notional: number;
  mtmPnl: number | null;
  unrealizedPnl: number | null;
  realizedPnl: number | null;
  exposurePct: number | null;
  quantitySource: string;
  parityState: string | null;
  parityBadge: ReturnType<typeof resolvePositionParityBadge>;
  executionMode: string | null;
  brokerStatus: string | null;
  stopLoss: number | null;
  targetPrice: number | null;
};

function fmtNum(v: number, dec = 2) {
  return new Intl.NumberFormat("en-IN", { minimumFractionDigits: dec, maximumFractionDigits: dec }).format(v);
}

export function PositionsPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [symbolQuery, setSymbolQuery] = useState("");
  const [sideFilter, setSideFilter] = useState<"ALL" | "LONG" | "SHORT">("ALL");
  const accessToken = useSessionStore((s) => s.accessToken);
  const userId = useSessionStore((s) => s.userId);
  useBrokerPositionSync(accessToken, userId, true);

  const exitMutation = usePositionExit();

  const modeQ = useQuery({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: fetchTraderExecutionMode,
    staleTime: 30_000,
  });

  const wsQ = useQuery<Workstation>({
    queryKey: ["positions-workstation", modeQ.data],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    refetchInterval: 2_000,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: true,
  });

  const brokerSessionLive = isBrokerSessionLive(wsQ.data?.brokerTruth);
  const pollMs = brokerPositionPollMs(brokerSessionLive);

  const exposureQ = useQuery({
    queryKey: ["portfolio-exposure"],
    queryFn: async () => (await api.get("/api/portfolio/exposure")).data?.data as Exposure,
    refetchInterval: pollMs,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: true,
  });

  const accountPnl = useMemo(
    () =>
      resolveAccountPnl({
        brokerTruth: wsQ.data?.brokerTruth,
        accountSummary: wsQ.data?.accountSummary,
        openPositions: wsQ.data?.openPositions,
      }),
    [wsQ.data],
  );

  const brokerConnected = brokerSessionLive;
  const syncPulseLive = isBrokerSyncPulseLive(
    wsQ.data?.brokerTruth?.lastSyncAt != null ? String(wsQ.data.brokerTruth.lastSyncAt) : null,
  );

  const brokerSyncState = wsQ.data?.brokerTruth?.syncState != null ? String(wsQ.data.brokerTruth.syncState) : null;
  const brokerSyncAge = formatBrokerSyncAge(
    wsQ.data?.brokerTruth?.lastSyncAt != null ? String(wsQ.data.brokerTruth.lastSyncAt) : null,
  );

  const mergedRows = useMemo<PositionRow[]>(() => {
    const openRows = wsQ.data?.openPositions ?? [];
    const mapped = openRows.map((ws) => {
      const symbol = String(ws.symbol ?? "").toUpperCase();
      const qty = parseMoney(ws.qty) ?? 0;
      const avgPrice = parseMoney(ws.avgPrice);
      const ltp = parseMoney(ws.ltp);
      const brokerQty = parseMoney(ws.brokerQty);
      const side = String(ws.side ?? (qty >= 0 ? "LONG" : "SHORT"));
      const parityState = ws.parityState != null ? String(ws.parityState).toUpperCase() : null;
      const quantitySource = resolvePositionQuantitySource(ws, brokerConnected);
      return {
        symbol,
        side,
        qty,
        brokerQty,
        avgPrice,
        ltp,
        notional: computePositionNotional(qty, ltp, avgPrice),
        mtmPnl: parseMoney(ws.mtmPnl),
        unrealizedPnl: parseMoney(ws.unrealizedPnl),
        realizedPnl: parseMoney(ws.realizedPnl),
        exposurePct: parseMoney(ws.exposurePct),
        quantitySource,
        parityState,
        parityBadge: resolvePositionParityBadge({ parityState, qty, brokerQty }, brokerConnected),
        executionMode: ws.executionMode != null ? String(ws.executionMode) : null,
        brokerStatus: ws.brokerStatus != null ? String(ws.brokerStatus) : null,
        stopLoss: parseMoney(ws.stopLoss),
        targetPrice: parseMoney(ws.targetPrice),
      };
    });
    return filterBrokerMirrorPositions(mapped, brokerSessionLive);
  }, [wsQ.data, brokerConnected, brokerSessionLive]);

  const filteredRows = useMemo(() => {
    let rows = mergedRows;
    if (symbolQuery.trim()) {
      const needle = symbolQuery.trim().toUpperCase();
      rows = rows.filter((r) => r.symbol.includes(needle));
    }
    if (sideFilter === "LONG") rows = rows.filter((r) => r.qty > 0);
    if (sideFilter === "SHORT") rows = rows.filter((r) => r.qty < 0);
    return rows;
  }, [mergedRows, symbolQuery, sideFilter]);

  const sortedRows = useMemo(() => {
    const arr = [...filteredRows];
    arr.sort((a, b) => (b.mtmPnl ?? 0) - (a.mtmPnl ?? 0));
    return arr;
  }, [filteredRows]);

  const summary = useMemo(() => {
    const totalSymbols = mergedRows.length;
    const netQty = mergedRows.reduce((s, r) => s + r.qty, 0);
    const grossNotional = mergedRows.reduce((s, r) => s + r.notional, 0);
    const totalMtm = mergedRows.reduce((s, r) => s + (r.mtmPnl ?? 0), 0);
    const top = [...mergedRows].sort((a, b) => b.notional - a.notional)[0];
    return { totalSymbols, netQty, grossNotional, totalMtm, top };
  }, [mergedRows]);

  const brokerRows = useMemo(() => {
    const rows = (exposureQ.data?.byBrokerNotional ?? []).map((r) => ({
      brokerVendor: r.brokerVendor,
      notionalNum: Number(r.tradedNotionalApprox || 0),
    }));
    const total = rows.reduce((s, r) => s + Math.abs(r.notionalNum), 0);
    return rows
      .sort((a, b) => Math.abs(b.notionalNum) - Math.abs(a.notionalNum))
      .map((r) => ({ ...r, sharePct: total > 0 ? (Math.abs(r.notionalNum) / total) * 100 : 0 }));
  }, [exposureQ.data?.byBrokerNotional]);

  function downloadCsv() {
    const headers = ["Symbol", "Side", "Qty", "Avg", "LTP", "MTM", "Unrealized", "Realized", "Notional", "Source", "Parity"];
    const lines = [
      headers.join(","),
      ...sortedRows.map((r) =>
        [
          r.symbol,
          r.side,
          r.qty,
          r.avgPrice ?? "",
          r.ltp ?? "",
          r.mtmPnl ?? "",
          r.unrealizedPnl ?? "",
          r.realizedPnl ?? "",
          r.notional,
          r.quantitySource,
          r.parityState ?? "",
        ].join(","),
      ),
    ];
    const blob = new Blob([lines.join("\n")], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "positions.csv";
    a.click();
  }

  const loading = exposureQ.isLoading || wsQ.isLoading;
  const inputCls = isLight
    ? "rounded-xl border border-neutral-200 bg-white px-3 py-2 text-sm shadow-sm outline-none focus:border-sky-400 focus:ring-2 focus:ring-sky-100"
    : "rounded-xl border border-neutral-700 bg-neutral-900 px-3 py-2 text-sm outline-none focus:border-sky-500";

  const content = (
    <>
      <div className="flex flex-wrap items-center gap-2 text-xs sm:text-sm">
        <PnlSourceBadge source={accountPnl.source} brokerConnected={brokerConnected} />
        {brokerConnected ? (
          <span
            className={cn(
              "rounded-xl border px-3 py-1.5 text-[10px] font-bold uppercase tracking-wide",
              brokerSyncState === "VERIFIED"
                ? isLight
                  ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                  : "border-emerald-500/30 bg-emerald-500/10 text-emerald-200"
                : isLight
                  ? "border-amber-200 bg-amber-50 text-amber-800"
                  : "border-amber-500/30 bg-amber-500/10 text-amber-200",
            )}
          >
            Zerodha {brokerSyncState ?? "SYNC"} {brokerSyncAge ? `· ${brokerSyncAge}` : ""}
          </span>
        ) : null}
        <button
          type="button"
          onClick={() => {
            void exposureQ.refetch();
            void wsQ.refetch();
          }}
          className={cn(
            "inline-flex items-center gap-1.5 rounded-xl border px-3 py-1.5 text-xs font-semibold",
            isLight ? "border-neutral-200 bg-white hover:bg-neutral-50" : "border-white/10 bg-white/5 hover:bg-white/10",
          )}
        >
          <RefreshCw className={cn("h-3.5 w-3.5", (exposureQ.isFetching || wsQ.isFetching) && "animate-spin")} />
          Refresh
        </button>
        {!embedded ? (
          <Link
            to="/terminal"
            className={cn(
              "inline-flex items-center gap-1 rounded-xl border px-3 py-1.5 text-xs font-semibold",
              isLight ? "border-indigo-200 bg-indigo-50 text-indigo-800" : "border-indigo-500/30 bg-indigo-500/10 text-indigo-300",
            )}
          >
            Terminal <ExternalLink className="h-3 w-3" />
          </Link>
        ) : null}
      </div>

      <PnlCommandRail
        mtm={accountPnl.mtm}
        unrealized={accountPnl.unrealized}
        realized={accountPnl.realized}
        loading={loading}
        openPositions={summary.totalSymbols}
        sublabel={`Net qty ${fmtNum(summary.netQty, 0)}`}
      />

      <div className="grid gap-4 sm:grid-cols-2">
        <AnimatedKpiCard label="Open symbols" loading={loading} value={String(summary.totalSymbols)} sublabel={`Net qty ${fmtNum(summary.netQty, 0)}`} icon={TrendingUp} accent="bg-amber-400" />
        <AnimatedKpiCard label="Gross notional" loading={loading} value={formatInr(summary.grossNotional)} sublabel={summary.top ? `Top · ${summary.top.symbol}` : undefined} accent="bg-indigo-400" />
      </div>

      <LivePositionsCommandTable
        title={`Live positions (${sortedRows.length})`}
        subtitle={
          brokerConnected
            ? "Zerodha terminal mirror · sync every 2s + push on change"
            : "Connect Zerodha to mirror broker terminal quantities and P&L"
        }
        syncPulseLive={brokerConnected && (syncPulseLive || wsQ.isFetching)}
        onExit={(symbol) => exitMutation.mutate(symbol)}
        exitingSymbol={exitMutation.isPending ? (exitMutation.variables ?? null) : null}
        rows={sortedRows.map((r): CommandPositionRow => ({
          symbol: r.symbol,
          side: r.side,
          qty: r.qty,
          avgPrice: r.avgPrice,
          ltp: r.ltp,
          mtmPnl: r.mtmPnl,
          unrealizedPnl: r.unrealizedPnl,
          realizedPnl: r.realizedPnl,
          notional: r.notional,
          quantitySource: r.quantitySource,
          parityBadge: r.parityBadge,
          stopLoss: r.stopLoss,
          targetPrice: r.targetPrice,
        }))}
        loading={loading}
        footerMtm={summary.totalMtm}
        showExtendedColumns
        action={
          <div className="flex flex-wrap items-center gap-2">
            <input className={inputCls} placeholder="Search symbol…" value={symbolQuery} onChange={(e) => setSymbolQuery(e.target.value)} />
            {(["ALL", "LONG", "SHORT"] as const).map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => setSideFilter(k)}
                className={cn(
                  "rounded-full px-2.5 py-1 text-[10px] font-bold uppercase tracking-wide",
                  sideFilter === k
                    ? "bg-neutral-900 text-white dark:bg-white dark:text-neutral-900"
                    : isLight ? "bg-neutral-100 text-neutral-600" : "bg-white/10 text-neutral-400",
                )}
              >
                {k}
              </button>
            ))}
            <button type="button" onClick={downloadCsv} className={cn("inline-flex items-center gap-1 rounded-lg border px-2 py-1 text-[10px] font-bold uppercase tracking-wide", isLight ? "border-neutral-200 bg-white" : "border-white/10")}>
              <Download className="h-3 w-3" /> Export
            </button>
          </div>
        }
      />

      <PremiumPanel title="Broker exposure mix">
        <div className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-3">
          {brokerRows.length === 0 ? (
            <EmptyState message="No broker notional breakdown yet" />
          ) : (
            brokerRows.map((r) => (
              <motion.div key={r.brokerVendor} variants={fadeUp} className={cn("rounded-xl border p-4", isLight ? "border-neutral-200 bg-neutral-50/50" : "border-white/10 bg-white/[0.03]")}>
                <div className="text-xs font-bold uppercase tracking-wide text-neutral-500">{r.brokerVendor}</div>
                <div className="mt-1 font-mono text-lg font-black tabular-nums">{formatInr(r.notionalNum)}</div>
                <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-neutral-200/80 dark:bg-neutral-800">
                  <motion.div
                    initial={{ width: 0 }}
                    animate={{ width: `${Math.max(4, r.sharePct)}%` }}
                    transition={{ duration: 0.6, ease: "easeOut" }}
                    className="h-full rounded-full bg-gradient-to-r from-sky-500 to-indigo-500"
                  />
                </div>
                <div className="mt-1 text-[11px] font-semibold text-neutral-500">{fmtNum(r.sharePct, 1)}% of routed volume</div>
              </motion.div>
            ))
          )}
        </div>
      </PremiumPanel>
    </>
  );

  if (embedded) return <div className="space-y-6">{content}</div>;

  return (
    <TraderPageShell
      title="Positions"
      subtitle={
        brokerConnected
          ? "Live Zerodha terminal mirror — quantities, MTM, and P&L stay aligned with your broker session."
          : "Connect Zerodha in Settings to mirror broker terminal positions in real time."
      }
    >
      {content}
    </TraderPageShell>
  );
}
