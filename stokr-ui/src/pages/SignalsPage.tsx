import { useQuery } from "@tanstack/react-query";
import {
  TRADER_EXECUTION_MODE_QUERY_KEY,
  fetchTraderExecutionMode,
  signalMatchesExecutionMode,
} from "../lib/traderExecutionMode";
import { fmtDateTime } from "../lib/dateUtils";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";
import { TrendingUp, TrendingDown, Minus, RefreshCw, X, ChevronRight, AlertTriangle } from "lucide-react";

type SignalRow = {
  id: string;
  createdAt: string | null;
  symbol: string | null;
  signalType: string | null;
  strategyName: string | null;
  reason: string | null;
  suggestedQty: string | null;
  confidenceScore: string | null;
  entryReferencePrice?: string | null;
  stopPrice?: string | null;
  targetPrice?: string | null;
  pipeline?: string | null;
  marketRegime?: string | null;
};

const fmtTime = fmtDateTime;

const fmt = (v: string | number | null | undefined, dec = 2) =>
  v == null || v === "" ? "—" : Number(v).toFixed(dec);

function SideChip({ type, light }: { type: string | null; light: boolean }) {
  const t = (type ?? "").toUpperCase();
  if (t === "BUY") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-bold",
      light ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/15 text-emerald-400")}>
      <TrendingUp className="h-3 w-3" /> BUY
    </span>
  );
  if (t === "SELL") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-bold",
      light ? "bg-rose-100 text-rose-700" : "bg-rose-500/15 text-rose-400")}>
      <TrendingDown className="h-3 w-3" /> SELL
    </span>
  );
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-bold",
      light ? "bg-neutral-100 text-neutral-500" : "bg-neutral-700/60 text-neutral-400")}>
      <Minus className="h-3 w-3" /> {t || "—"}
    </span>
  );
}

function SignalDetailDrawer({ signal, onClose, light }: { signal: SignalRow; onClose: () => void; light: boolean }) {
  const bg = light ? "bg-white" : "bg-neutral-950";
  const border = light ? "border-neutral-200" : "border-white/[0.07]";
  const cardBg = light ? "bg-neutral-50" : "bg-white/[0.04]";
  const labelCls = "text-[10px] font-semibold uppercase tracking-widest " + (light ? "text-neutral-400" : "text-neutral-500");
  const valueCls = "mt-1 font-mono text-sm " + (light ? "text-neutral-900" : "text-white");

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className={cn("relative z-10 flex h-full w-full max-w-xl flex-col shadow-2xl overflow-hidden", bg, "ring-1", border)}>
        <div className={cn("flex items-center justify-between border-b px-5 py-3.5", border, light ? "bg-neutral-50" : "bg-neutral-900/80")}>
          <div className="flex items-center gap-2">
            <span className={cn("font-bold text-sm", light ? "text-neutral-900" : "text-white")}>
              {signal.strategyName ?? "Signal"}
            </span>
            <SideChip type={signal.signalType} light={light} />
          </div>
          <button type="button" onClick={onClose} className={cn("rounded-lg p-1.5", light ? "text-neutral-400 hover:bg-neutral-100" : "text-neutral-400 hover:bg-white/[0.07]")}>
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          <div className="grid grid-cols-2 gap-3">
            {[
              { label: "Symbol", value: signal.symbol ?? "—" },
              { label: "Time", value: fmtTime(signal.createdAt) },
              { label: "Entry Price", value: fmt(signal.entryReferencePrice) },
              { label: "Stop Loss", value: <span className={light ? "text-rose-600" : "text-rose-400"}>{fmt(signal.stopPrice)}</span> },
              { label: "Target", value: <span className={light ? "text-emerald-600" : "text-emerald-400"}>{fmt(signal.targetPrice)}</span> },
              { label: "Qty", value: fmt(signal.suggestedQty) },
              { label: "Confidence", value: signal.confidenceScore != null ? `${(Number(signal.confidenceScore) * 100).toFixed(1)}%` : "—" },
              { label: "Regime", value: signal.marketRegime ?? "—" },
            ].map(({ label, value }) => (
              <div key={label} className={cn("rounded-xl px-3 py-2.5", cardBg)}>
                <div className={labelCls}>{label}</div>
                <div className={valueCls}>{value}</div>
              </div>
            ))}
          </div>
          {signal.reason && (
            <div className={cn("rounded-xl border px-4 py-3", border, cardBg)}>
              <div className={cn("mb-1", labelCls)}>Rationale</div>
              <div className={cn("text-xs leading-relaxed", light ? "text-neutral-700" : "text-neutral-300")}>{signal.reason}</div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export function SignalsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const navigate = useNavigate();
  const [symbol, setSymbol] = useState("");
  const [signalType, setSignalType] = useState("ALL");
  const [selectedSignal, setSelectedSignal] = useState<SignalRow | null>(null);

  const modeQ = useQuery({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: fetchTraderExecutionMode,
    staleTime: 30_000,
  });
  const executionMode = modeQ.data ?? "PAPER";

  const q = useQuery<SignalRow[]>({
    queryKey: ["trader-signals-feed", executionMode],
    queryFn: async () => {
      const res = await api.get("/api/trader/strategy-feed?limit=500");
      return (Array.isArray(res.data?.data) ? res.data.data : []) as SignalRow[];
    },
    refetchInterval: 15_000,
  });

  const rows = (q.data ?? []).filter((r) => {
    const symOk = !symbol.trim() || (r.symbol ?? "").toUpperCase().includes(symbol.toUpperCase());
    const typeOk = signalType === "ALL" || (r.signalType ?? "").toUpperCase() === signalType;
    return symOk && typeOk && signalMatchesExecutionMode(r, executionMode);
  });

  const bg = isLight ? "bg-white" : "bg-neutral-950";
  const borderCls = isLight ? "border-neutral-200" : "border-white/[0.07]";
  const hdrBg = isLight ? "bg-neutral-50/95" : "bg-neutral-900/95";
  const rowHover = isLight ? "hover:bg-neutral-50" : "hover:bg-white/[0.04]";
  const rowAlt = isLight ? "bg-neutral-50/50" : "bg-white/[0.01]";
  const textMuted = isLight ? "text-neutral-400" : "text-neutral-500";
  const inputCls = cn(
    "rounded-lg border px-3 py-1.5 text-xs outline-none focus:ring-1",
    isLight
      ? "border-neutral-200 bg-white text-neutral-900 placeholder-neutral-400 focus:border-blue-400 focus:ring-blue-100"
      : "border-white/[0.1] bg-white/[0.05] text-white placeholder-neutral-500 focus:border-sky-500/60"
  );
  const selectCls = cn(
    "rounded-lg border px-2 py-1.5 text-xs outline-none",
    isLight ? "border-neutral-200 bg-white text-neutral-800" : "border-white/[0.1] bg-neutral-900 text-white"
  );

  return (
    <div className="flex h-full flex-col space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className={cn("text-xl font-bold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
            My Signals
          </h1>
          <p className={cn("mt-0.5 text-xs", textMuted)}>
            Strategy-generated signals on your subscriptions · {rows.length} shown · auto-refresh 15s
          </p>
        </div>
        <button
          type="button"
          onClick={() => void q.refetch()}
          className={cn("flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold transition",
            isLight ? "border-neutral-200 bg-white text-neutral-600 hover:bg-neutral-50" : "border-white/[0.1] bg-white/[0.05] text-neutral-300 hover:bg-white/[0.09]")}
        >
          <RefreshCw className={cn("h-3.5 w-3.5", q.isFetching && "animate-spin")} /> Refresh
        </button>
      </div>

      {/* Filters */}
      <div className={cn("flex flex-wrap items-center gap-2 rounded-xl border px-4 py-3",
        isLight ? "border-neutral-200 bg-neutral-50" : "border-white/[0.07] bg-white/[0.03]")}>
        <input className={inputCls} placeholder="Filter symbol…" value={symbol} onChange={e => setSymbol(e.target.value)} />
        <select className={selectCls} value={signalType} onChange={e => setSignalType(e.target.value)}>
          <option value="ALL">All sides</option>
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
          <option value="HOLD">HOLD</option>
        </select>
        {(symbol || signalType !== "ALL") && (
          <button type="button" onClick={() => { setSymbol(""); setSignalType("ALL"); }}
            className={cn("flex items-center gap-1 text-xs", textMuted, "hover:text-red-500")}>
            <X className="h-3 w-3" /> Clear
          </button>
        )}
      </div>

      {/* Empty state */}
      {!q.isLoading && rows.length === 0 && (
        <div className={cn("flex flex-col items-center gap-3 rounded-xl border px-6 py-10 text-center",
          isLight ? "border-amber-200 bg-amber-50 text-amber-800" : "border-amber-500/25 bg-amber-500/[0.07] text-amber-300")}>
          <AlertTriangle className="h-8 w-8 opacity-60" />
          <div>
            <div className="font-semibold">No signals yet</div>
            <div className="mt-1 text-xs opacity-80">
              Signals appear here once the market opens and your strategies begin scanning.
            </div>
          </div>
          <div className="flex gap-2 mt-1">
            <button type="button" onClick={() => navigate("/strategies")}
              className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold",
                isLight ? "border-amber-400 bg-white text-amber-800 hover:bg-amber-50" : "border-amber-500/40 text-amber-200 hover:bg-amber-500/15")}>
              My Strategies
            </button>
            <button type="button" onClick={() => navigate("/intraday")}
              className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold",
                isLight ? "border-neutral-300 bg-white text-neutral-700 hover:bg-neutral-50" : "border-white/[0.1] text-neutral-300 hover:bg-white/[0.07]")}>
              Intraday Terminal
            </button>
          </div>
        </div>
      )}

      {/* Table */}
      {(q.isLoading || rows.length > 0) && (
        <div className={cn("flex-1 overflow-auto rounded-xl border", borderCls, bg)}>
          <table className="w-full min-w-[700px] text-xs">
            <thead className={cn("sticky top-0 z-10 border-b", borderCls, hdrBg)}>
              <tr>
                {["Time", "Strategy", "Symbol", "Side", "Entry", "SL", "Target", "Qty", "Confidence", "Rationale"].map(h => (
                  <th key={h} className={cn("whitespace-nowrap px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-widest", textMuted)}>{h}</th>
                ))}
                <th className="w-8" />
              </tr>
            </thead>
            <tbody>
              {q.isLoading && (
                <tr><td colSpan={11} className={cn("px-4 py-12 text-center", textMuted)}>Loading signals…</td></tr>
              )}
              {rows.map((r, i) => (
                <tr key={r.id}
                  onClick={() => setSelectedSignal(r)}
                  className={cn(
                    "cursor-pointer border-b transition-colors", borderCls,
                    i % 2 === 0 ? rowAlt : "",
                    rowHover,
                    selectedSignal?.id === r.id && (isLight ? "bg-blue-50" : "bg-sky-500/[0.07]")
                  )}>
                  <td className={cn("whitespace-nowrap px-3 py-2.5 font-mono", textMuted)}>{fmtTime(r.createdAt)}</td>
                  <td className={cn("max-w-[140px] truncate px-3 py-2.5 font-medium", isLight ? "text-neutral-800" : "text-white")}
                    title={r.strategyName ?? ""}>{r.strategyName ?? "—"}</td>
                  <td className={cn("px-3 py-2.5 font-mono font-bold", isLight ? "text-neutral-900" : "text-white")}>{r.symbol ?? "—"}</td>
                  <td className="px-3 py-2.5"><SideChip type={r.signalType} light={isLight} /></td>
                  <td className={cn("px-3 py-2.5 font-mono", isLight ? "text-neutral-700" : "text-neutral-300")}>{fmt(r.entryReferencePrice)}</td>
                  <td className={cn("px-3 py-2.5 font-mono", isLight ? "text-rose-600" : "text-rose-400")}>{fmt(r.stopPrice)}</td>
                  <td className={cn("px-3 py-2.5 font-mono", isLight ? "text-emerald-600" : "text-emerald-400")}>{fmt(r.targetPrice)}</td>
                  <td className={cn("px-3 py-2.5 font-mono", isLight ? "text-neutral-600" : "text-neutral-300")}>{fmt(r.suggestedQty)}</td>
                  <td className={cn("px-3 py-2.5 font-mono", isLight ? "text-neutral-600" : "text-neutral-300")}>
                    {r.confidenceScore != null ? `${(Number(r.confidenceScore) * 100).toFixed(1)}%` : "—"}
                  </td>
                  <td className={cn("max-w-[180px] truncate px-3 py-2.5", textMuted)} title={r.reason ?? ""}>{r.reason ?? "—"}</td>
                  <td className={cn("px-3 py-2.5", textMuted)}><ChevronRight className="h-3.5 w-3.5" /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedSignal && (
        <SignalDetailDrawer signal={selectedSignal} onClose={() => setSelectedSignal(null)} light={isLight} />
      )}
    </div>
  );
}
