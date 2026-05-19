import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api/client";
import { cn } from "../lib/utils";
import {
  X, ChevronRight, RefreshCw, AlertTriangle,
  TrendingUp, TrendingDown, Minus,
} from "lucide-react";

// ─── Types ────────────────────────────────────────────────────────────────────

type OrderRow = {
  id: string;
  userId: string | null;
  correlationId: string | null;
  signalId: string | null;
  strategyKey: string | null;
  executionMode: string | null;
  symbol: string | null;
  side: string | null;
  orderType: string | null;
  quantity: number | null;
  limitPrice: number | null;
  state: string | null;
  brokerVendor: string | null;
  rejectReason: string | null;
  createdAt: string | null;
};

type ExecutionEvent = {
  id: string;
  eventType: string;
  eventPayloadJson: string | null;
  streamSequence: number | null;
  correlationId: string | null;
  createdAt: string;
};

type PageResp = { content: OrderRow[]; totalPages: number; totalElements: number; page: number };

// ─── Helpers ──────────────────────────────────────────────────────────────────

const fmtTime = (iso: string | null) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit" })
    + " " + d.toLocaleDateString("en-IN", { day: "2-digit", month: "short" });
};

const SideChip = ({ side }: { side: string | null }) => {
  const s = (side ?? "").toUpperCase();
  if (s === "BUY") return (
    <span className="inline-flex items-center gap-1 rounded-md bg-emerald-500/15 px-2 py-0.5 text-[11px] font-bold text-emerald-400">
      <TrendingUp className="h-3 w-3" /> BUY
    </span>
  );
  if (s === "SELL") return (
    <span className="inline-flex items-center gap-1 rounded-md bg-rose-500/15 px-2 py-0.5 text-[11px] font-bold text-rose-400">
      <TrendingDown className="h-3 w-3" /> SELL
    </span>
  );
  return (
    <span className="inline-flex items-center gap-1 rounded-md bg-neutral-700/60 px-2 py-0.5 text-[11px] font-bold text-neutral-400">
      <Minus className="h-3 w-3" /> {s || "—"}
    </span>
  );
};

const StateChip = ({ state }: { state: string | null }) => {
  const s = (state ?? "").toUpperCase();
  const cls =
    s === "FILLED" || s === "EXIT_FILLED" ? "bg-emerald-500/15 text-emerald-400" :
    s === "REJECTED" ? "bg-rose-500/15 text-rose-400" :
    s === "PARTIALLY_FILLED" ? "bg-amber-500/15 text-amber-400" :
    s === "CREATED" || s === "VALIDATED" || s === "SUBMITTED" || s === "ACCEPTED" ? "bg-sky-500/15 text-sky-400" :
    s === "CANCELLED" || s === "EXPIRED" ? "bg-neutral-600/40 text-neutral-400" :
    "bg-neutral-700/50 text-neutral-400";
  return (
    <span className={cn("rounded-md px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide", cls)}>
      {s || "—"}
    </span>
  );
};

const ModeChip = ({ mode }: { mode: string | null }) => {
  const v = (mode ?? "").toUpperCase();
  const cls = v === "LIVE" ? "bg-rose-500/15 text-rose-400" :
              v === "PAPER" ? "bg-amber-500/15 text-amber-400" :
              "bg-neutral-700/50 text-neutral-400";
  return <span className={cn("rounded-md px-1.5 py-0.5 text-[10px] font-bold", cls)}>{v || "—"}</span>;
};

const eventColor = (type: string) => {
  if (type.includes("FILLED") || type.includes("FILL")) return "bg-emerald-500";
  if (type.includes("REJECT")) return "bg-rose-500";
  if (type.includes("RISK") || type.includes("PASS")) return "bg-sky-400";
  if (type.includes("DLQ") || type.includes("RETRY")) return "bg-amber-400";
  return "bg-neutral-500";
};

// ─── Order Detail Drawer ──────────────────────────────────────────────────────

function OrderDetailDrawer({ order, onClose }: { order: OrderRow; onClose: () => void }) {
  const eventsQ = useQuery<ExecutionEvent[]>({
    queryKey: ["admin-order-events", order.id],
    queryFn: async () => (await api.get(`/api/admin/oms/orders/${order.id}/events`)).data?.data ?? [],
    staleTime: 30_000,
  });
  const timelineQ = useQuery<Array<{ eventType: string; streamSequence: number | null; createdAt: string; payload: Record<string, unknown> }>>({
    queryKey: ["admin-order-timeline", order.id],
    queryFn: async () => (await api.get(`/api/admin/operations/orders/${order.id}/execution-timeline`)).data?.data ?? [],
    staleTime: 30_000,
  });

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative z-10 flex h-full w-full max-w-2xl flex-col bg-neutral-950 shadow-2xl ring-1 ring-white/10 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-white/[0.07] bg-neutral-900/80 px-5 py-3.5">
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-mono text-sm font-bold text-white">{order.symbol}</span>
              <SideChip side={order.side} />
              <StateChip state={order.state} />
              <ModeChip mode={order.executionMode} />
            </div>
            <div className="mt-0.5 font-mono text-xs text-neutral-500">{order.id.slice(0, 20)}…</div>
          </div>
          <button type="button" onClick={onClose} className="rounded-lg p-1.5 text-neutral-400 hover:bg-white/[0.07] hover:text-white">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {/* Order Info */}
          <div className="grid grid-cols-3 gap-3">
            {[
              { label: "Strategy", value: order.strategyKey ?? "—" },
              { label: "Order Type", value: order.orderType ?? "—" },
              { label: "Quantity", value: String(order.quantity ?? "—") },
              { label: "Limit Price", value: order.limitPrice != null ? String(order.limitPrice) : "—" },
              { label: "Broker", value: order.brokerVendor ?? "—" },
              { label: "Created", value: fmtTime(order.createdAt) },
            ].map(({ label, value }) => (
              <div key={label} className="rounded-xl bg-white/[0.04] px-3 py-2.5">
                <div className="text-[10px] font-semibold uppercase tracking-widest text-neutral-500">{label}</div>
                <div className="mt-1 font-mono text-xs text-white break-all">{value}</div>
              </div>
            ))}
          </div>

          {/* Reject reason */}
          {order.rejectReason && (
            <div className="flex items-start gap-2 rounded-xl border border-rose-500/25 bg-rose-500/[0.08] px-4 py-3 text-xs text-rose-300">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>{order.rejectReason}</span>
            </div>
          )}

          {/* IDs */}
          <div className="space-y-1.5 rounded-xl border border-white/[0.07] bg-white/[0.02] px-4 py-3">
            <div className="text-[10px] font-bold uppercase tracking-widest text-neutral-500">Reference IDs</div>
            {[
              { label: "Order ID", value: order.id },
              { label: "Correlation", value: order.correlationId },
              { label: "Signal ID", value: order.signalId },
              { label: "User ID", value: order.userId },
            ].filter(r => r.value).map(({ label, value }) => (
              <div key={label} className="flex gap-2 text-xs">
                <span className="w-24 shrink-0 text-neutral-500">{label}</span>
                <span className="font-mono text-neutral-300 break-all">{value}</span>
              </div>
            ))}
          </div>

          {/* Execution Events */}
          {(eventsQ.data?.length ?? 0) > 0 && (
            <div>
              <div className="mb-2 text-[11px] font-bold uppercase tracking-widest text-neutral-400">
                Execution Events ({eventsQ.data!.length})
              </div>
              <div className="rounded-xl border border-white/[0.07] overflow-hidden">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-white/[0.06] bg-white/[0.03]">
                      {["#", "Event", "Correlation", "Time"].map(h => (
                        <th key={h} className="px-3 py-2 text-left text-[10px] font-bold uppercase tracking-wide text-neutral-500">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {eventsQ.data!.map((ev) => (
                      <tr key={ev.id} className="border-b border-white/[0.04]">
                        <td className="px-3 py-2 text-neutral-600">{ev.streamSequence ?? "—"}</td>
                        <td className="px-3 py-2">
                          <span className={cn("rounded-md px-1.5 py-0.5 text-[10px] font-bold text-black", eventColor(ev.eventType))}>
                            {ev.eventType}
                          </span>
                        </td>
                        <td className="px-3 py-2 font-mono text-neutral-500 text-[11px]">
                          {ev.correlationId ? ev.correlationId.slice(0, 16) + "…" : "—"}
                        </td>
                        <td className="px-3 py-2 text-neutral-500">{fmtTime(ev.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Execution Timeline */}
          {(timelineQ.data?.length ?? 0) > 0 && (
            <div>
              <div className="mb-2 text-[11px] font-bold uppercase tracking-widest text-neutral-400">Timeline</div>
              <div className="space-y-1">
                {timelineQ.data!.map((ev, i) => (
                  <div key={i} className="flex gap-3">
                    <div className="flex flex-col items-center">
                      <div className={cn("h-2 w-2 rounded-full mt-1 shrink-0", eventColor(ev.eventType))} />
                      {i < timelineQ.data!.length - 1 && <div className="flex-1 w-px bg-white/[0.08] mt-1" />}
                    </div>
                    <div className="pb-3 flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-bold text-neutral-200">{ev.eventType}</span>
                        <span className="ml-auto text-[10px] text-neutral-500">{fmtTime(ev.createdAt)}</span>
                      </div>
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

export function AdminOmsMonitorPage() {
  const [page, setPage] = useState(0);
  const [symbol, setSymbol] = useState("");
  const [strategy, setStrategy] = useState("");
  const [state, setState] = useState("ALL");
  const [mode, setMode] = useState("ALL");
  const [selectedOrder, setSelectedOrder] = useState<OrderRow | null>(null);

  const q = useQuery<PageResp>({
    queryKey: ["admin-oms-orders-v2", page, symbol, strategy, state, mode],
    queryFn: async () => {
      const p = new URLSearchParams();
      p.set("page", String(page));
      p.set("size", "50");
      p.set("sort", "createdAt,desc");
      if (symbol.trim()) p.set("symbol", symbol.trim());
      if (strategy.trim()) p.set("strategyKey", strategy.trim());
      if (state !== "ALL") p.set("state", state);
      if (mode !== "ALL") p.set("executionMode", mode);
      const res = await api.get(`/api/admin/oms/orders?${p.toString()}`);
      return res.data?.data as PageResp;
    },
    refetchInterval: 15_000,
  });

  const rows = q.data?.content ?? [];
  const total = q.data?.totalElements ?? 0;
  const totalPages = q.data?.totalPages ?? 1;

  const inputCls = "rounded-lg border border-white/[0.1] bg-white/[0.05] px-3 py-1.5 text-xs text-white placeholder-neutral-500 outline-none focus:border-sky-500/60";
  const selectCls = "rounded-lg border border-white/[0.1] bg-neutral-900 px-2 py-1.5 text-xs text-white outline-none focus:border-sky-500/60";

  return (
    <div className="flex h-full flex-col space-y-3 text-foreground">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-white">OMS Monitor</h1>
          <p className="mt-0.5 text-xs text-neutral-500">
            All platform orders across users · {total.toLocaleString()} total · auto-refresh 15s
          </p>
        </div>
        <button
          type="button"
          onClick={() => void q.refetch()}
          className="flex items-center gap-1.5 rounded-lg border border-white/[0.1] bg-white/[0.05] px-3 py-1.5 text-xs font-semibold text-neutral-300 hover:bg-white/[0.09]"
        >
          <RefreshCw className={cn("h-3.5 w-3.5", q.isFetching && "animate-spin")} /> Refresh
        </button>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-2 rounded-xl border border-white/[0.07] bg-white/[0.03] px-4 py-3">
        <input className={inputCls} placeholder="Symbol…" value={symbol} onChange={e => { setSymbol(e.target.value); setPage(0); }} />
        <input className={inputCls} placeholder="Strategy key…" value={strategy} onChange={e => { setStrategy(e.target.value); setPage(0); }} />
        <select className={selectCls} value={state} onChange={e => { setState(e.target.value); setPage(0); }}>
          <option value="ALL">All states</option>
          {["CREATED","VALIDATED","SUBMITTED","ACCEPTED","PARTIALLY_FILLED","FILLED","REJECTED","CANCELLED","EXPIRED","FAILED"].map(s => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <select className={selectCls} value={mode} onChange={e => { setMode(e.target.value); setPage(0); }}>
          <option value="ALL">All modes</option>
          <option value="LIVE">LIVE</option>
          <option value="PAPER">PAPER</option>
          <option value="SIMULATED">SIMULATED</option>
        </select>
        {(symbol || strategy || state !== "ALL" || mode !== "ALL") && (
          <button type="button" onClick={() => { setSymbol(""); setStrategy(""); setState("ALL"); setMode("ALL"); setPage(0); }}
            className="flex items-center gap-1 text-xs text-neutral-500 hover:text-white">
            <X className="h-3 w-3" /> Clear
          </button>
        )}
      </div>

      {/* Table */}
      <div className="flex-1 overflow-auto rounded-xl border border-white/[0.07] bg-neutral-950">
        <table className="w-full min-w-[900px] text-xs">
          <thead className="sticky top-0 z-10 border-b border-white/[0.07] bg-neutral-900/95">
            <tr>
              {["Time", "User", "Symbol", "Side", "Qty", "Price", "State", "Mode", "Strategy", "Broker", "Reject Reason"].map(h => (
                <th key={h} className="whitespace-nowrap px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-widest text-neutral-500">{h}</th>
              ))}
              <th className="w-8" />
            </tr>
          </thead>
          <tbody>
            {q.isLoading && (
              <tr><td colSpan={12} className="px-4 py-12 text-center text-neutral-500">Loading orders…</td></tr>
            )}
            {!q.isLoading && rows.length === 0 && (
              <tr><td colSpan={12} className="px-4 py-12 text-center text-neutral-600">
                <AlertTriangle className="mx-auto mb-2 h-6 w-6" />No orders found
              </td></tr>
            )}
            {rows.map((r, i) => (
              <tr
                key={r.id}
                onClick={() => setSelectedOrder(r)}
                className={cn(
                  "cursor-pointer border-b border-white/[0.04] transition-colors",
                  i % 2 === 0 ? "bg-white/[0.01]" : "",
                  "hover:bg-white/[0.05]",
                  selectedOrder?.id === r.id && "bg-sky-500/[0.07]"
                )}
              >
                <td className="whitespace-nowrap px-3 py-2 font-mono text-neutral-400">{fmtTime(r.createdAt)}</td>
                <td className="max-w-[90px] truncate px-3 py-2 font-mono text-neutral-400 text-[10px]" title={r.userId ?? ""}>
                  {r.userId ? r.userId.slice(0, 8) + "…" : "—"}
                </td>
                <td className="px-3 py-2 font-mono font-bold text-white">{r.symbol ?? "—"}</td>
                <td className="px-3 py-2"><SideChip side={r.side} /></td>
                <td className="px-3 py-2 font-mono text-neutral-300">{r.quantity ?? "—"}</td>
                <td className="px-3 py-2 font-mono text-neutral-300">{r.limitPrice ?? "—"}</td>
                <td className="px-3 py-2"><StateChip state={r.state} /></td>
                <td className="px-3 py-2"><ModeChip mode={r.executionMode} /></td>
                <td className="max-w-[130px] truncate px-3 py-2 text-neutral-400" title={r.strategyKey ?? ""}>{r.strategyKey ?? "—"}</td>
                <td className="px-3 py-2 text-neutral-500">{r.brokerVendor ?? "—"}</td>
                <td className="max-w-[140px] truncate px-3 py-2 text-rose-400 text-[10px]" title={r.rejectReason ?? ""}>{r.rejectReason ?? "—"}</td>
                <td className="px-3 py-2 text-neutral-600"><ChevronRight className="h-3.5 w-3.5" /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="flex items-center justify-between text-xs text-neutral-500">
        <span>{total > 0 ? `${page * 50 + 1}–${Math.min((page + 1) * 50, total)} of ${total.toLocaleString()}` : "0 results"}</span>
        <div className="flex items-center gap-2">
          <button type="button" disabled={page <= 0} onClick={() => setPage(p => Math.max(0, p - 1))}
            className="rounded-md border border-white/[0.1] px-3 py-1 hover:bg-white/[0.06] disabled:opacity-30">Prev</button>
          <span className="tabular-nums">Page {page + 1} / {totalPages}</span>
          <button type="button" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}
            className="rounded-md border border-white/[0.1] px-3 py-1 hover:bg-white/[0.06] disabled:opacity-30">Next</button>
        </div>
      </div>

      {selectedOrder && <OrderDetailDrawer order={selectedOrder} onClose={() => setSelectedOrder(null)} />}
    </div>
  );
}
