import { useQuery } from "@tanstack/react-query";
import { useState, useCallback } from "react";
import { api } from "../../api/client";
import { cn } from "../../lib/utils";
import {
  X, ChevronRight, RefreshCw, Download, AlertTriangle,
  TrendingUp, TrendingDown, Minus, Clock, Layers, User,
} from "lucide-react";

// ─── Types ────────────────────────────────────────────────────────────────────

type SignalRow = {
  id: string;
  strategyName: string | null;
  symbol: string | null;
  signalType: string | null;
  pipeline: string | null;
  confidenceScore: number | null;
  entryReferencePrice: number | null;
  stopPrice: number | null;
  targetPrice: number | null;
  suggestedQty: number | null;
  reason: string | null;
  marketRegime: string | null;
  userId: string | null;
  createdAt: string | null;
};

type OrderSummary = {
  id: string; symbol: string; side: string; state: string;
  quantity: number; executionMode: string; rejectReason: string | null; createdAt: string;
};

type TimelineEvent = {
  eventType: string; streamSequence: number | null; createdAt: string;
  payload: Record<string, unknown>;
};

type SignalDetail = {
  signal: SignalRow;
  linkedOrders: OrderSummary[];
  executionTimeline: TimelineEvent[];
};

type PageResp = { content: SignalRow[]; totalPages: number; totalElements: number; page: number };

// ─── Helpers ──────────────────────────────────────────────────────────────────

const fmt = (v: number | null | undefined, dec = 2) =>
  v == null ? "—" : Number(v).toFixed(dec);

const fmtTime = (iso: string | null) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit" })
    + " " + d.toLocaleDateString("en-IN", { day: "2-digit", month: "short" });
};

const SideChip = ({ type }: { type: string | null }) => {
  if (!type) return <span className="text-neutral-400">—</span>;
  const t = type.toUpperCase();
  if (t === "BUY") return (
    <span className="inline-flex items-center gap-1 rounded-md bg-emerald-500/15 px-2 py-0.5 text-[11px] font-bold text-emerald-400">
      <TrendingUp className="h-3 w-3" /> BUY
    </span>
  );
  if (t === "SELL") return (
    <span className="inline-flex items-center gap-1 rounded-md bg-rose-500/15 px-2 py-0.5 text-[11px] font-bold text-rose-400">
      <TrendingDown className="h-3 w-3" /> SELL
    </span>
  );
  return (
    <span className="inline-flex items-center gap-1 rounded-md bg-neutral-700/60 px-2 py-0.5 text-[11px] font-bold text-neutral-400">
      <Minus className="h-3 w-3" /> {t}
    </span>
  );
};

const StateChip = ({ state }: { state: string | null }) => {
  const s = (state ?? "").toUpperCase();
  const cls =
    s === "FILLED" || s === "ORDER_FILLED" ? "bg-emerald-500/15 text-emerald-400" :
    s === "REJECTED" || s === "EXECUTION_REJECTED" ? "bg-rose-500/15 text-rose-400" :
    s === "PARTIAL_FILL" || s === "PARTIALLY_FILLED" ? "bg-amber-500/15 text-amber-400" :
    s === "CREATED" || s === "VALIDATED" || s === "SUBMITTED" ? "bg-sky-500/15 text-sky-400" :
    "bg-neutral-700/50 text-neutral-400";
  return (
    <span className={cn("rounded-md px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide", cls)}>
      {s || "—"}
    </span>
  );
};

const PipelineChip = ({ p }: { p: string | null }) => {
  const v = (p ?? "").toUpperCase();
  const cls = v === "LIVE" ? "bg-rose-500/15 text-rose-400" :
              v === "PAPER" ? "bg-amber-500/15 text-amber-400" :
              "bg-neutral-700/50 text-neutral-400";
  return (
    <span className={cn("rounded-md px-1.5 py-0.5 text-[10px] font-bold", cls)}>{v || "—"}</span>
  );
};

// ─── Signal Detail Drawer ─────────────────────────────────────────────────────

function SignalDetailDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const q = useQuery<SignalDetail>({
    queryKey: ["admin-signal-detail", id],
    queryFn: async () => (await api.get(`/api/admin/signals/${id}`)).data?.data,
    staleTime: 30_000,
  });

  const d = q.data;
  const sig = d?.signal;

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative z-10 flex h-full w-full max-w-2xl flex-col bg-neutral-950 shadow-2xl ring-1 ring-white/10 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-white/[0.07] bg-neutral-900/80 px-5 py-3.5">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-sm font-bold text-white">{sig?.strategyName ?? "Signal"}</span>
              {sig && <SideChip type={sig.signalType} />}
              {sig && <PipelineChip p={sig.pipeline} />}
            </div>
            <div className="mt-0.5 font-mono text-xs text-neutral-500">{id.slice(0, 20)}…</div>
          </div>
          <button type="button" onClick={onClose} className="rounded-lg p-1.5 text-neutral-400 hover:bg-white/[0.07] hover:text-white">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {q.isLoading && (
            <div className="flex h-32 items-center justify-center text-neutral-500 text-sm">Loading…</div>
          )}

          {sig && (
            <>
              {/* Key metrics */}
              <div className="grid grid-cols-3 gap-3">
                {[
                  { label: "Symbol", value: sig.symbol ?? "—" },
                  { label: "Side", value: <SideChip type={sig.signalType} /> },
                  { label: "Mode", value: <PipelineChip p={sig.pipeline} /> },
                  { label: "Entry Price", value: fmt(sig.entryReferencePrice) },
                  { label: "Stop Loss", value: <span className="text-rose-400">{fmt(sig.stopPrice)}</span> },
                  { label: "Target", value: <span className="text-emerald-400">{fmt(sig.targetPrice)}</span> },
                  { label: "Quantity", value: fmt(sig.suggestedQty) },
                  { label: "Confidence", value: sig.confidenceScore != null ? `${(Number(sig.confidenceScore) * 100).toFixed(1)}%` : "—" },
                  { label: "Regime", value: sig.marketRegime ?? "—" },
                ].map(({ label, value }) => (
                  <div key={label} className="rounded-xl bg-white/[0.04] px-3 py-2.5">
                    <div className="text-[10px] font-semibold uppercase tracking-widest text-neutral-500">{label}</div>
                    <div className="mt-1 font-mono text-sm text-white">{value}</div>
                  </div>
                ))}
              </div>

              {/* Reason */}
              {sig.reason && (
                <div className="rounded-xl border border-white/[0.07] bg-white/[0.03] px-4 py-3">
                  <div className="mb-1 text-[10px] font-bold uppercase tracking-widest text-neutral-500">Signal Rationale</div>
                  <div className="text-xs text-neutral-300 leading-relaxed">{sig.reason}</div>
                </div>
              )}

              {/* Time + User */}
              <div className="flex gap-4 text-xs text-neutral-500">
                <span className="flex items-center gap-1"><Clock className="h-3 w-3" /> {fmtTime(sig.createdAt)}</span>
                {sig.userId && <span className="flex items-center gap-1"><User className="h-3 w-3" /> {sig.userId.slice(0, 8)}…</span>}
              </div>
            </>
          )}

          {/* Linked Orders */}
          {(d?.linkedOrders?.length ?? 0) > 0 && (
            <div>
              <div className="mb-2 text-[11px] font-bold uppercase tracking-widest text-neutral-400">Linked Orders</div>
              <div className="rounded-xl border border-white/[0.07] overflow-hidden">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-white/[0.06] bg-white/[0.03]">
                      {["Symbol", "Side", "Qty", "Mode", "State", "Reject", "Time"].map(h => (
                        <th key={h} className="px-3 py-2 text-left font-semibold uppercase tracking-wide text-neutral-500">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {d!.linkedOrders.map((o) => (
                      <tr key={o.id} className="border-b border-white/[0.04] hover:bg-white/[0.03]">
                        <td className="px-3 py-2 font-mono text-white">{o.symbol}</td>
                        <td className="px-3 py-2"><SideChip type={o.side} /></td>
                        <td className="px-3 py-2 font-mono text-neutral-300">{o.quantity}</td>
                        <td className="px-3 py-2"><PipelineChip p={o.executionMode} /></td>
                        <td className="px-3 py-2"><StateChip state={o.state} /></td>
                        <td className="px-3 py-2 text-rose-400 max-w-[120px] truncate">{o.rejectReason ?? "—"}</td>
                        <td className="px-3 py-2 text-neutral-500">{fmtTime(o.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Execution Timeline */}
          {(d?.executionTimeline?.length ?? 0) > 0 && (
            <div>
              <div className="mb-2 text-[11px] font-bold uppercase tracking-widest text-neutral-400">Execution Timeline</div>
              <div className="space-y-2">
                {d!.executionTimeline.map((ev, i) => (
                  <div key={i} className="flex gap-3">
                    <div className="flex flex-col items-center">
                      <div className="h-2 w-2 rounded-full bg-sky-500 mt-1 shrink-0" />
                      {i < d!.executionTimeline.length - 1 && (
                        <div className="flex-1 w-px bg-white/[0.08] mt-1" />
                      )}
                    </div>
                    <div className="pb-3 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-bold text-sky-300">{ev.eventType}</span>
                        <span className="text-[10px] text-neutral-600">seq {ev.streamSequence ?? "?"}</span>
                        <span className="ml-auto text-[10px] text-neutral-500">{fmtTime(ev.createdAt)}</span>
                      </div>
                      {ev.payload && Object.keys(ev.payload).length > 0 && (
                        <div className="mt-1 rounded-lg bg-white/[0.03] px-2 py-1.5 font-mono text-[10px] text-neutral-400 break-all">
                          {JSON.stringify(ev.payload)}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export function AdminSignalsPage() {
  const [page, setPage] = useState(0);
  const [symbol, setSymbol] = useState("");
  const [strategyName, setStrategyName] = useState("");
  const [signalType, setSignalType] = useState("ALL");
  const [pipeline, setPipeline] = useState("ALL");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const q = useQuery<PageResp>({
    queryKey: ["admin-signals", page, symbol, strategyName, signalType, pipeline],
    queryFn: async () => {
      const p = new URLSearchParams();
      p.set("page", String(page));
      p.set("size", "50");
      p.set("sort", "createdAt,desc");
      if (symbol.trim()) p.set("symbol", symbol.trim());
      if (strategyName.trim()) p.set("strategyName", strategyName.trim());
      if (signalType !== "ALL") p.set("signalType", signalType);
      if (pipeline !== "ALL") p.set("pipeline", pipeline);
      const res = await api.get(`/api/admin/signals?${p.toString()}`);
      return res.data?.data as PageResp;
    },
    refetchInterval: 15_000,
  });

  const handleExport = useCallback(() => {
    const rows = q.data?.content ?? [];
    const header = ["time", "strategy", "symbol", "side", "mode", "confidence", "entry", "sl", "target", "qty", "reason"];
    const csv = [
      header.join(","),
      ...rows.map(r => [
        r.createdAt, r.strategyName, r.symbol, r.signalType, r.pipeline,
        r.confidenceScore, r.entryReferencePrice, r.stopPrice, r.targetPrice,
        r.suggestedQty, `"${(r.reason ?? "").replace(/"/g, "'")}"`
      ].join(","))
    ].join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    a.download = `signals_${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
  }, [q.data]);

  const rows = q.data?.content ?? [];
  const total = q.data?.totalElements ?? 0;
  const totalPages = q.data?.totalPages ?? 1;

  const inputCls = "rounded-lg border border-white/[0.1] bg-white/[0.05] px-3 py-1.5 text-xs text-white placeholder-neutral-500 outline-none focus:border-sky-500/60 focus:bg-white/[0.08]";
  const selectCls = "rounded-lg border border-white/[0.1] bg-neutral-900 px-2 py-1.5 text-xs text-white outline-none focus:border-sky-500/60";

  return (
    <div className="flex h-full flex-col space-y-3 text-foreground">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-white">Signal Monitor</h1>
          <p className="mt-0.5 text-xs text-neutral-500">
            All strategy-generated signals · live feed · {total.toLocaleString()} total
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => void q.refetch()}
            className="flex items-center gap-1.5 rounded-lg border border-white/[0.1] bg-white/[0.05] px-3 py-1.5 text-xs font-semibold text-neutral-300 hover:bg-white/[0.09]"
          >
            <RefreshCw className={cn("h-3.5 w-3.5", q.isFetching && "animate-spin")} /> Refresh
          </button>
          <button
            type="button"
            onClick={handleExport}
            className="flex items-center gap-1.5 rounded-lg border border-white/[0.1] bg-white/[0.05] px-3 py-1.5 text-xs font-semibold text-neutral-300 hover:bg-white/[0.09]"
          >
            <Download className="h-3.5 w-3.5" /> CSV
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-2 rounded-xl border border-white/[0.07] bg-white/[0.03] px-4 py-3">
        <input
          className={inputCls}
          placeholder="Symbol…"
          value={symbol}
          onChange={e => { setSymbol(e.target.value); setPage(0); }}
        />
        <input
          className={inputCls}
          placeholder="Strategy…"
          value={strategyName}
          onChange={e => { setStrategyName(e.target.value); setPage(0); }}
        />
        <select className={selectCls} value={signalType} onChange={e => { setSignalType(e.target.value); setPage(0); }}>
          <option value="ALL">All sides</option>
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
          <option value="HOLD">HOLD</option>
        </select>
        <select className={selectCls} value={pipeline} onChange={e => { setPipeline(e.target.value); setPage(0); }}>
          <option value="ALL">All modes</option>
          <option value="LIVE">LIVE</option>
          <option value="PAPER">PAPER</option>
          <option value="SIMULATED">SIMULATED</option>
        </select>
        {(symbol || strategyName || signalType !== "ALL" || pipeline !== "ALL") && (
          <button
            type="button"
            onClick={() => { setSymbol(""); setStrategyName(""); setSignalType("ALL"); setPipeline("ALL"); setPage(0); }}
            className="flex items-center gap-1 text-xs text-neutral-500 hover:text-white"
          >
            <X className="h-3 w-3" /> Clear
          </button>
        )}
        <span className="ml-auto text-xs text-neutral-600">auto-refresh 15s</span>
      </div>

      {/* Table */}
      <div className="flex-1 overflow-auto rounded-xl border border-white/[0.07] bg-neutral-950">
        <table className="w-full min-w-[900px] text-xs">
          <thead className="sticky top-0 z-10 border-b border-white/[0.07] bg-neutral-900/95">
            <tr>
              {["Time", "Strategy", "Symbol", "Side", "Confidence", "Entry", "SL", "Target", "Qty", "Mode", "Reason"].map(h => (
                <th key={h} className="whitespace-nowrap px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-widest text-neutral-500">
                  {h}
                </th>
              ))}
              <th className="w-8" />
            </tr>
          </thead>
          <tbody>
            {q.isLoading && (
              <tr><td colSpan={12} className="px-4 py-12 text-center text-neutral-500">Loading signals…</td></tr>
            )}
            {!q.isLoading && rows.length === 0 && (
              <tr><td colSpan={12} className="px-4 py-12 text-center text-neutral-600">
                <AlertTriangle className="mx-auto mb-2 h-6 w-6" />
                No signals found
              </td></tr>
            )}
            {rows.map((r, i) => (
              <tr
                key={r.id}
                onClick={() => setSelectedId(r.id)}
                className={cn(
                  "cursor-pointer border-b border-white/[0.04] transition-colors",
                  i % 2 === 0 ? "bg-white/[0.01]" : "",
                  "hover:bg-white/[0.05]",
                  selectedId === r.id && "bg-sky-500/[0.07]"
                )}
              >
                <td className="whitespace-nowrap px-3 py-2 font-mono text-neutral-400">{fmtTime(r.createdAt)}</td>
                <td className="max-w-[140px] truncate px-3 py-2 font-medium text-white" title={r.strategyName ?? ""}>
                  {r.strategyName ?? "—"}
                </td>
                <td className="px-3 py-2 font-mono font-bold text-white">{r.symbol ?? "—"}</td>
                <td className="px-3 py-2"><SideChip type={r.signalType} /></td>
                <td className="px-3 py-2 font-mono text-neutral-300">
                  {r.confidenceScore != null ? `${(Number(r.confidenceScore) * 100).toFixed(1)}%` : "—"}
                </td>
                <td className="px-3 py-2 font-mono text-neutral-300">{fmt(r.entryReferencePrice)}</td>
                <td className="px-3 py-2 font-mono text-rose-400">{fmt(r.stopPrice)}</td>
                <td className="px-3 py-2 font-mono text-emerald-400">{fmt(r.targetPrice)}</td>
                <td className="px-3 py-2 font-mono text-neutral-300">{fmt(r.suggestedQty)}</td>
                <td className="px-3 py-2"><PipelineChip p={r.pipeline} /></td>
                <td className="max-w-[180px] truncate px-3 py-2 text-neutral-500" title={r.reason ?? ""}>
                  {r.reason ?? "—"}
                </td>
                <td className="px-3 py-2 text-neutral-600">
                  <ChevronRight className="h-3.5 w-3.5" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="flex items-center justify-between text-xs text-neutral-500">
        <span>
          {total > 0 ? `${page * 50 + 1}–${Math.min((page + 1) * 50, total)} of ${total.toLocaleString()}` : "0 results"}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={page <= 0}
            onClick={() => setPage(p => Math.max(0, p - 1))}
            className="rounded-md border border-white/[0.1] px-3 py-1 hover:bg-white/[0.06] disabled:opacity-30"
          >
            Prev
          </button>
          <span className="tabular-nums">Page {page + 1} / {totalPages}</span>
          <button
            type="button"
            disabled={page >= totalPages - 1}
            onClick={() => setPage(p => p + 1)}
            className="rounded-md border border-white/[0.1] px-3 py-1 hover:bg-white/[0.06] disabled:opacity-30"
          >
            Next
          </button>
        </div>
      </div>

      {/* Detail Drawer */}
      {selectedId && (
        <SignalDetailDrawer id={selectedId} onClose={() => setSelectedId(null)} />
      )}
    </div>
  );
}
