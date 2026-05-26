import { useMemo, useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { api } from "../api/client";
import { TRADER_EXECUTION_MODE_QUERY_KEY, fetchTraderExecutionMode } from "../lib/traderExecutionMode";
import {
  formatInr,
  formatPnlDisplay,
  parseMoney,
  pnlToneClass,
  resolveAccountPnl,
} from "../lib/moneyUtils";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import {
  AnimatedKpiCard,
  EmptyState,
  PnlCell,
  PnlSourceBadge,
  PremiumPanel,
  SideBadge,
  TraderPageShell,
  fadeUp,
} from "../components/trader/TraderPremium";
import { ArrowUpDown, Download, ExternalLink, RefreshCw, TrendingUp } from "lucide-react";

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
  executionMode: string | null;
  brokerStatus: string | null;
};

function fmtNum(v: number, dec = 2) {
  return new Intl.NumberFormat("en-IN", { minimumFractionDigits: dec, maximumFractionDigits: dec }).format(v);
}

export function PositionsPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [symbolQuery, setSymbolQuery] = useState("");
  const [sideFilter, setSideFilter] = useState<"ALL" | "LONG" | "SHORT">("ALL");
  const [sortBy, setSortBy] = useState<"symbol" | "mtm" | "notional">("mtm");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");
  const queryClient = useQueryClient();

  useEffect(() => {
    const controller = new AbortController();
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    let buffer = "";
    fetch("/api/admin/operations/stream", {
      headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
      signal: controller.signal,
    })
      .then(async (res) => {
        if (!res.ok || !res.body) return;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let sep: number;
          while ((sep = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, sep).trim();
            buffer = buffer.slice(sep + 2);
            if (frame && (frame.includes("tick") || frame.includes("ops_realtime"))) {
              void queryClient.invalidateQueries({ queryKey: ["portfolio-exposure"] });
              void queryClient.invalidateQueries({ queryKey: ["positions-workstation"] });
            }
          }
        }
      })
      .catch(() => {});
    return () => controller.abort();
  }, [queryClient]);

  const modeQ = useQuery({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: fetchTraderExecutionMode,
    staleTime: 30_000,
  });

  const exposureQ = useQuery({
    queryKey: ["portfolio-exposure"],
    queryFn: async () => (await api.get("/api/portfolio/exposure")).data?.data as Exposure,
    refetchInterval: 8_000,
  });

  const wsQ = useQuery<Workstation>({
    queryKey: ["positions-workstation", modeQ.data],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    refetchInterval: 8_000,
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

  const brokerConnected =
    accountPnl.source === "BROKER" ||
    String(wsQ.data?.accountSummary?.brokerConnectionState ?? "").includes("CONNECTED");

  const mergedRows = useMemo<PositionRow[]>(() => {
    const wsBySymbol = new Map<string, Record<string, unknown>>();
    for (const row of wsQ.data?.openPositions ?? []) {
      const sym = String(row.symbol ?? "").toUpperCase();
      if (sym) wsBySymbol.set(sym, row);
    }
    const exposureRows = exposureQ.data?.bySymbol ?? [];
    const symbols = new Set<string>();
    exposureRows.forEach((r) => symbols.add(r.symbol.toUpperCase()));
    wsBySymbol.forEach((_, sym) => symbols.add(sym));

    return Array.from(symbols).map((symbol) => {
      const exp = exposureRows.find((r) => r.symbol.toUpperCase() === symbol);
      const ws = wsBySymbol.get(symbol);
      const qty = parseMoney(ws?.qty ?? exp?.quantity) ?? 0;
      const side = String(ws?.side ?? (qty >= 0 ? "LONG" : "SHORT"));
      return {
        symbol,
        side,
        qty,
        brokerQty: parseMoney(ws?.brokerQty),
        avgPrice: parseMoney(ws?.avgPrice),
        ltp: parseMoney(ws?.ltp),
        notional: Math.abs(parseMoney(exp?.exposureNotional) ?? parseMoney(ws?.exposureNotional) ?? 0),
        mtmPnl: parseMoney(ws?.mtmPnl),
        unrealizedPnl: parseMoney(ws?.unrealizedPnl),
        realizedPnl: parseMoney(ws?.realizedPnl),
        exposurePct: parseMoney(ws?.exposurePct),
        quantitySource: String(exp?.quantitySource ?? ws?.quantitySource ?? "OMS").toUpperCase(),
        parityState: exp?.parityState ? String(exp.parityState).toUpperCase() : null,
        executionMode: ws?.executionMode != null ? String(ws.executionMode) : null,
        brokerStatus: ws?.brokerStatus != null ? String(ws.brokerStatus) : null,
      };
    });
  }, [exposureQ.data, wsQ.data]);

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
    arr.sort((a, b) => {
      let cmp = 0;
      if (sortBy === "symbol") cmp = a.symbol.localeCompare(b.symbol);
      if (sortBy === "mtm") cmp = (a.mtmPnl ?? 0) - (b.mtmPnl ?? 0);
      if (sortBy === "notional") cmp = a.notional - b.notional;
      return sortDir === "asc" ? cmp : -cmp;
    });
    return arr;
  }, [filteredRows, sortBy, sortDir]);

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

  function toggleSort(next: typeof sortBy) {
    if (sortBy === next) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else {
      setSortBy(next);
      setSortDir(next === "symbol" ? "asc" : "desc");
    }
  }

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
      <div className="flex flex-wrap items-center gap-2">
        <PnlSourceBadge source={accountPnl.source} brokerConnected={brokerConnected} />
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

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <AnimatedKpiCard label="MTM P&L" loading={loading} value={formatPnlDisplay(accountPnl.mtm)} pnlValue={accountPnl.mtm} accent={(accountPnl.mtm ?? 0) >= 0 ? "bg-emerald-500" : "bg-rose-500"} />
        <AnimatedKpiCard label="Unrealized" loading={loading} value={formatPnlDisplay(accountPnl.unrealized)} pnlValue={accountPnl.unrealized} accent="bg-sky-400" />
        <AnimatedKpiCard label="Realized" loading={loading} value={formatPnlDisplay(accountPnl.realized)} pnlValue={accountPnl.realized} accent="bg-violet-400" />
        <AnimatedKpiCard label="Open symbols" loading={loading} value={String(summary.totalSymbols)} sublabel={`Net qty ${fmtNum(summary.netQty, 0)}`} icon={TrendingUp} accent="bg-amber-400" />
        <AnimatedKpiCard label="Gross notional" loading={loading} value={formatInr(summary.grossNotional)} sublabel={summary.top ? `Top · ${summary.top.symbol}` : undefined} accent="bg-indigo-400" />
      </div>

      <PremiumPanel
        title="Live positions"
        action={
          <div className="flex flex-wrap items-center gap-2">
            <input className={inputCls} placeholder="Search symbol…" value={symbolQuery} onChange={(e) => setSymbolQuery(e.target.value)} />
            {(["ALL", "LONG", "SHORT"] as const).map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => setSideFilter(k)}
                className={cn(
                  "rounded-full px-2.5 py-1 text-[11px] font-bold uppercase",
                  sideFilter === k
                    ? "bg-neutral-900 text-white dark:bg-white dark:text-neutral-900"
                    : isLight ? "bg-neutral-100 text-neutral-600" : "bg-white/10 text-neutral-400",
                )}
              >
                {k}
              </button>
            ))}
            <button type="button" onClick={downloadCsv} className={cn("inline-flex items-center gap-1 rounded-lg border px-2 py-1 text-[11px] font-semibold", isLight ? "border-neutral-200" : "border-white/10")}>
              <Download className="h-3 w-3" /> CSV
            </button>
          </div>
        }
      >
        <div className="overflow-x-auto px-2 pb-2">
          <table className="min-w-full text-left text-xs">
            <thead className={cn("sticky top-0 z-10 text-[10px] uppercase tracking-wider", isLight ? "bg-white text-neutral-500" : "bg-neutral-900 text-neutral-400")}>
              <tr>
                {[
                  ["symbol", "Symbol"],
                  ["mtm", "MTM P&L"],
                  ["side", "Side"],
                  ["qty", "Qty"],
                  ["avg", "Avg"],
                  ["ltp", "LTP"],
                  ["unrealized", "Unrealized"],
                  ["realized", "Realized"],
                  ["notional", "Notional"],
                  ["source", "Source"],
                ].map(([key, label]) => (
                  <th key={key} className="px-3 py-3 font-semibold">
                    {key === "symbol" || key === "mtm" || key === "notional" ? (
                      <button type="button" className="inline-flex items-center gap-1 hover:opacity-80" onClick={() => toggleSort(key as typeof sortBy)}>
                        {label} <ArrowUpDown className="h-3 w-3 opacity-50" />
                      </button>
                    ) : (
                      label
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={10} className="px-3 py-8 text-center text-neutral-500">Loading positions…</td></tr>
              ) : sortedRows.length === 0 ? (
                <tr><td colSpan={10}><EmptyState message="No open positions match your filters" /></td></tr>
              ) : (
                sortedRows.map((r, i) => (
                  <motion.tr
                    key={r.symbol}
                    initial={{ opacity: 0, x: -8 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: i * 0.03, duration: 0.25 }}
                    className={cn("border-t transition hover:bg-sky-500/[0.04]", isLight ? "border-neutral-100" : "border-white/[0.06]")}
                  >
                    <td className="px-3 py-3">
                      <div className="font-mono font-bold">{r.symbol}</div>
                      {r.parityState === "MISMATCH" ? (
                        <span className="mt-1 inline-block rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-bold text-amber-700">PARITY MISMATCH</span>
                      ) : null}
                    </td>
                    <td className="px-3 py-3"><PnlCell value={r.mtmPnl} /></td>
                    <td className="px-3 py-3"><SideBadge side={r.side} /></td>
                    <td className={cn("px-3 py-3 font-mono font-semibold tabular-nums", pnlToneClass(r.qty, isLight))}>{fmtNum(r.qty, 0)}</td>
                    <td className="px-3 py-3 font-mono tabular-nums">{r.avgPrice != null ? fmtNum(r.avgPrice) : "—"}</td>
                    <td className="px-3 py-3 font-mono tabular-nums">{r.ltp != null ? fmtNum(r.ltp) : "—"}</td>
                    <td className="px-3 py-3"><PnlCell value={r.unrealizedPnl} /></td>
                    <td className="px-3 py-3"><PnlCell value={r.realizedPnl} /></td>
                    <td className="px-3 py-3 font-mono tabular-nums">{formatInr(r.notional)}</td>
                    <td className="px-3 py-3">
                      <span className={cn("rounded-md px-2 py-0.5 text-[10px] font-bold uppercase", r.quantitySource === "BROKER" ? "bg-emerald-500/15 text-emerald-700" : "bg-neutral-500/10 text-neutral-600")}>
                        {r.quantitySource}
                      </span>
                    </td>
                  </motion.tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </PremiumPanel>

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
      subtitle="Live broker-backed quantities and mark-to-market — synced from your workstation when Zerodha is connected."
    >
      {content}
    </TraderPageShell>
  );
}
