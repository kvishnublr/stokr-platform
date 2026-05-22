import { useMutation, useQuery } from "@tanstack/react-query";
import { fmtDateTime } from "../lib/dateUtils";
import { useState } from "react";
import { api } from "../api/client";
import {
  TrendingUp, TrendingDown, Minus, RefreshCw, X,
  ChevronRight, AlertTriangle, Zap, Activity,
  CheckCircle2, XCircle, Clock, Timer, BarChart3,
  ShieldAlert, Trash2,
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
  id: string; eventType: string; eventPayloadJson: string | null;
  streamSequence: number | null; correlationId: string | null; createdAt: string;
};

type TimelineEvent = {
  eventType: string; streamSequence: number | null; createdAt: string;
  payload: Record<string, unknown>;
};

type PageResp = { content: OrderRow[]; totalPages: number; totalElements: number; page: number };

type OmsStatsResp = {
  totalToday: number; filledToday: number; rejectedToday: number;
  partialToday: number; cancelledToday: number; pendingToday: number; totalAllTime: number;
};

type StrategyCatalogRow = {
  strategyKey: string;
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

const fmtTime = fmtDateTime;

const evDotCls = (type: string) => {
  if (type.includes("FILLED") || type.includes("FILL")) return "bg-emerald-500";
  if (type.includes("REJECT")) return "bg-rose-500";
  if (type.includes("RISK") || type.includes("PASS")) return "bg-sky-400";
  if (type.includes("DLQ") || type.includes("RETRY")) return "bg-amber-400";
  return "bg-slate-400";
};

const evBadgeCls = (type: string) => {
  if (type.includes("FILLED")) return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (type.includes("REJECT")) return "border-rose-200 bg-rose-50 text-rose-700";
  if (type.includes("RISK") || type.includes("PASS")) return "border-sky-200 bg-sky-50 text-sky-700";
  if (type.includes("DLQ") || type.includes("RETRY")) return "border-amber-200 bg-amber-50 text-amber-700";
  return "border-slate-200 bg-slate-50 text-slate-500";
};

// ─── Chips ────────────────────────────────────────────────────────────────────

function SideChip({ side }: { side: string | null }) {
  const s = (side ?? "").toUpperCase();
  if (s === "BUY") return (
    <span className="inline-flex items-center gap-1 rounded-md border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-700">
      <TrendingUp className="h-3 w-3" /> BUY
    </span>
  );
  if (s === "SELL") return (
    <span className="inline-flex items-center gap-1 rounded-md border border-rose-200 bg-rose-50 px-2 py-0.5 text-[11px] font-semibold text-rose-700">
      <TrendingDown className="h-3 w-3" /> SELL
    </span>
  );
  return (
    <span className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-500">
      <Minus className="h-3 w-3" /> {s || "—"}
    </span>
  );
}

const STATE_CFG: Record<string, string> = {
  FILLED:           "border-emerald-200 bg-emerald-50 text-emerald-700",
  EXIT_FILLED:      "border-emerald-200 bg-emerald-50 text-emerald-700",
  PARTIALLY_FILLED: "border-amber-200 bg-amber-50 text-amber-700",
  REJECTED:         "border-rose-200 bg-rose-50 text-rose-700",
  FAILED:           "border-red-200 bg-red-50 text-red-700",
  CREATED:          "border-blue-200 bg-blue-50 text-blue-700",
  VALIDATED:        "border-sky-200 bg-sky-50 text-sky-700",
  SUBMITTED:        "border-sky-200 bg-sky-50 text-sky-700",
  ACCEPTED:         "border-sky-200 bg-sky-50 text-sky-700",
  CANCELLED:        "border-slate-200 bg-slate-100 text-slate-500",
  EXPIRED:          "border-slate-200 bg-slate-100 text-slate-500",
};

function StateChip({ state }: { state: string | null }) {
  const s = (state ?? "").toUpperCase();
  const cls = STATE_CFG[s] ?? "border-slate-200 bg-slate-50 text-slate-400";
  return (
    <span className={`rounded-md border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${cls}`}>
      {s || "—"}
    </span>
  );
}

function ModeChip({ mode }: { mode: string | null }) {
  const v = (mode ?? "").toUpperCase();
  if (v === "LIVE") return (
    <span className="inline-flex items-center gap-1 rounded-md border border-rose-200 bg-rose-50 px-1.5 py-0.5 text-[10px] font-bold text-rose-600">
      <span className="h-1.5 w-1.5 rounded-full bg-rose-500 animate-pulse" />LIVE
    </span>
  );
  if (v === "PAPER") return (
    <span className="rounded-md border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-bold text-amber-700">PAPER</span>
  );
  return <span className="rounded-md border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-[10px] font-semibold text-slate-400">{v || "—"}</span>;
}

// ─── Stat Card ────────────────────────────────────────────────────────────────

function StatCard({ label, value, sub, accent, icon: Icon }: {
  label: string; value: string | number; sub?: string;
  accent: string; icon: React.ElementType;
}) {
  return (
    <div className={`bg-white rounded-xl border border-slate-200 shadow-sm px-4 py-3 flex items-start gap-3 border-l-4 ${accent}`}>
      <div className="mt-0.5 shrink-0">
        <Icon className="h-4 w-4 text-slate-400" />
      </div>
      <div className="min-w-0">
        <div className="text-[10px] font-semibold uppercase tracking-widest text-slate-400 whitespace-nowrap">{label}</div>
        <div className="mt-0.5 text-xl font-bold tabular-nums text-slate-900 leading-none">{value}</div>
        {sub && <div className="mt-0.5 text-[11px] text-slate-500">{sub}</div>}
      </div>
    </div>
  );
}

// ─── Order Detail Drawer ──────────────────────────────────────────────────────

function OrderDetailDrawer({ order, onClose }: { order: OrderRow; onClose: () => void }) {
  const eventsQ = useQuery<ExecutionEvent[]>({
    queryKey: ["admin-order-events", order.id],
    queryFn: async () => (await api.get(`/api/admin/oms/orders/${order.id}/events`)).data?.data ?? [],
    staleTime: 30_000,
  });
  const timelineQ = useQuery<TimelineEvent[]>({
    queryKey: ["admin-order-timeline", order.id],
    queryFn: async () => (await api.get(`/api/admin/operations/orders/${order.id}/execution-timeline`)).data?.data ?? [],
    staleTime: 30_000,
  });

  const label = "text-[10px] font-semibold uppercase tracking-widest text-slate-400";
  const val = "mt-0.5 text-sm font-mono text-slate-900 break-all";

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-slate-900/30 backdrop-blur-[2px]" onClick={onClose} />
      <div className="relative z-10 flex h-full w-full max-w-2xl flex-col bg-white shadow-2xl ring-1 ring-slate-200 overflow-hidden">

        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 bg-slate-50 px-5 py-4">
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-mono text-base font-bold text-slate-900">{order.symbol}</span>
              <SideChip side={order.side} />
              <StateChip state={order.state} />
              <ModeChip mode={order.executionMode} />
            </div>
            <div className="mt-1 font-mono text-[11px] text-slate-400">{order.id.slice(0, 24)}…</div>
          </div>
          <button type="button" onClick={onClose}
            className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">

          {/* Reject reason banner */}
          {order.rejectReason && (
            <div className="mx-5 mt-4 flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3">
              <ShieldAlert className="mt-0.5 h-4 w-4 text-rose-500 shrink-0" />
              <div>
                <div className="text-[10px] font-bold uppercase tracking-widest text-rose-500 mb-0.5">Rejection Reason</div>
                <span className="text-sm text-rose-700 font-medium">{order.rejectReason}</span>
              </div>
            </div>
          )}

          {/* Order Parameters */}
          <div className="border-b border-slate-100 px-5 py-4">
            <div className="mb-3 flex items-center gap-2">
              <Activity className="h-3.5 w-3.5 text-slate-400" />
              <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Order Parameters</span>
            </div>
            <div className="grid grid-cols-3 gap-3">
              {[
                { label: "Strategy", value: order.strategyKey ?? "—" },
                { label: "Order Type", value: order.orderType ?? "—" },
                { label: "Quantity", value: String(order.quantity ?? "—") },
                { label: "Limit Price", value: order.limitPrice != null ? String(order.limitPrice) : "—" },
                { label: "Broker", value: order.brokerVendor ?? "—" },
                { label: "Created", value: fmtTime(order.createdAt) },
              ].map(({ label: lbl, value }) => (
                <div key={lbl} className="rounded-lg bg-slate-50 border border-slate-100 px-3 py-2.5">
                  <div className={label}>{lbl}</div>
                  <div className={val}>{value}</div>
                </div>
              ))}
            </div>
          </div>

          {/* Reference IDs */}
          <div className="border-b border-slate-100 px-5 py-4">
            <div className="mb-3 flex items-center gap-2">
              <Clock className="h-3.5 w-3.5 text-slate-400" />
              <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Reference IDs</span>
            </div>
            <div className="space-y-2 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
              {[
                { label: "Order ID", value: order.id },
                { label: "Correlation", value: order.correlationId },
                { label: "Signal ID", value: order.signalId },
                { label: "User ID", value: order.userId },
              ].filter(r => r.value).map(({ label: lbl, value }) => (
                <div key={lbl} className="flex gap-3 text-xs">
                  <span className="w-24 shrink-0 font-semibold text-slate-400">{lbl}</span>
                  <span className="font-mono text-slate-700 break-all select-all">{value}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Execution Events */}
          {(eventsQ.data?.length ?? 0) > 0 && (
            <div className="border-b border-slate-100 px-5 py-4">
              <div className="mb-3 flex items-center gap-2">
                <Zap className="h-3.5 w-3.5 text-slate-400" />
                <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">
                  Execution Events ({eventsQ.data!.length})
                </span>
              </div>
              <div className="rounded-xl border border-slate-200 overflow-hidden">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-slate-200 bg-slate-50">
                      {["Seq", "Event", "Correlation", "Time"].map(h => (
                        <th key={h} className="px-3 py-2 text-left text-[10px] font-semibold uppercase tracking-wide text-slate-400">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {eventsQ.data!.map((ev) => (
                      <tr key={ev.id} className="hover:bg-slate-50">
                        <td className="px-3 py-2 font-mono text-slate-400 text-[11px]">{ev.streamSequence ?? "—"}</td>
                        <td className="px-3 py-2">
                          <span className={`rounded-md border px-1.5 py-0.5 text-[10px] font-semibold ${evBadgeCls(ev.eventType)}`}>
                            {ev.eventType}
                          </span>
                        </td>
                        <td className="px-3 py-2 font-mono text-slate-400 text-[11px]">
                          {ev.correlationId ? ev.correlationId.slice(0, 16) + "…" : "—"}
                        </td>
                        <td className="px-3 py-2 text-slate-500 text-[11px]">{fmtTime(ev.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Execution Timeline */}
          {(timelineQ.data?.length ?? 0) > 0 && (
            <div className="px-5 py-4">
              <div className="mb-3 flex items-center gap-2">
                <Timer className="h-3.5 w-3.5 text-slate-400" />
                <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Execution Timeline</span>
              </div>
              <div>
                {timelineQ.data!.map((ev, i) => (
                  <div key={i} className="flex gap-4">
                    <div className="flex flex-col items-center pt-1 w-4 shrink-0">
                      <div className={`h-2.5 w-2.5 rounded-full ring-2 ring-white ${evDotCls(ev.eventType)}`} />
                      {i < timelineQ.data!.length - 1 && (
                        <div className="flex-1 w-px bg-slate-200 mt-1" style={{ minHeight: 20 }} />
                      )}
                    </div>
                    <div className="pb-4 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-semibold text-slate-700">{ev.eventType}</span>
                        <span className="text-[10px] text-slate-400">{fmtTime(ev.createdAt)}</span>
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

const ORDER_STATES = ["CREATED","VALIDATED","SUBMITTED","ACCEPTED","PARTIALLY_FILLED","FILLED","REJECTED","CANCELLED","EXPIRED","FAILED"];

export function AdminOmsMonitorPage() {
  const [page, setPage] = useState(0);
  const [symbol, setSymbol] = useState("");
  const [strategy, setStrategy] = useState("");
  const [state, setState] = useState("ALL");
  const [mode, setMode] = useState("ALL");
  const [selectedOrder, setSelectedOrder] = useState<OrderRow | null>(null);

  const statsQ = useQuery<OmsStatsResp>({
    queryKey: ["admin-oms-stats"],
    queryFn: async () => (await api.get("/api/admin/oms/stats")).data?.data,
    refetchInterval: 30_000,
    staleTime: 15_000,
  });

  const rejectReasonsQ = useQuery<{ reason: string; count: number }[]>({
    queryKey: ["admin-oms-reject-reasons"],
    queryFn: async () => (await api.get("/api/admin/oms/reject-reasons")).data?.data ?? [],
    refetchInterval: 60_000,
    staleTime: 30_000,
  });

  const strategiesQ = useQuery<StrategyCatalogRow[]>({
    queryKey: ["admin-oms-strategy-options"],
    queryFn: async () => {
      const res = await api.get("/api/admin/strategies?page=0&size=500&sort=strategyKey");
      const content = res.data?.data?.content;
      return Array.isArray(content) ? (content as StrategyCatalogRow[]) : [];
    },
    staleTime: 60_000,
  });

  const q = useQuery<PageResp>({
    queryKey: ["admin-oms-orders-v3", page, symbol, strategy, state, mode],
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

  const hasFilters = !!(symbol || strategy || state !== "ALL" || mode !== "ALL");
  const clearFilters = () => { setSymbol(""); setStrategy(""); setState("ALL"); setMode("ALL"); setPage(0); };

  const expireMut = useMutation({
    mutationFn: async () => {
      const r = await api.post("/api/admin/oms/stuck-orders/expire?stuckMinutes=5");
      return r.data?.data as { expired: number };
    },
    onSuccess: () => { void q.refetch(); void statsQ.refetch(); },
  });

  const rows = q.data?.content ?? [];
  const total = q.data?.totalElements ?? 0;
  const totalPages = q.data?.totalPages ?? 1;
  const stats = statsQ.data;
  const strategyOptions = Array.from(
    new Set([
      ...(strategiesQ.data ?? []).map((s) => String(s.strategyKey ?? "").trim()).filter(Boolean),
      ...rows.map((r) => String(r.strategyKey ?? "").trim()).filter(Boolean),
    ]),
  ).sort((a, b) => a.localeCompare(b));

  const fillRate = stats?.totalToday ? Math.round((stats.filledToday / stats.totalToday) * 100) : 0;

  const rowStateBg = (s: string | null) => {
    const v = (s ?? "").toUpperCase();
    if (v === "REJECTED" || v === "FAILED") return "bg-rose-50/40";
    if (v === "FILLED" || v === "EXIT_FILLED") return "bg-emerald-50/30";
    if (v === "PARTIALLY_FILLED") return "bg-amber-50/30";
    return "";
  };

  const inputCls = "h-9 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-900 placeholder-slate-400 outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-100 transition-colors";
  const selectCls = "h-9 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-800 outline-none focus:border-blue-400 transition-colors cursor-pointer";

  return (
    <div className="flex h-full flex-col bg-slate-50">

      {/* ── Page Header ─────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-4">
        <div className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-lg font-bold tracking-tight text-slate-900">OMS Monitor</h1>
            <p className="mt-0.5 text-xs text-slate-500">
              Platform-wide order surveillance · {total.toLocaleString()} results · live feed
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button type="button" onClick={() => expireMut.mutate()} disabled={expireMut.isPending}
              className="inline-flex items-center gap-1.5 rounded-lg border border-rose-300 bg-rose-50 px-3 py-1.5 text-xs font-semibold text-rose-700 hover:bg-rose-100 disabled:opacity-50 transition-colors shadow-sm">
              {expireMut.isPending
                ? <><RefreshCw className="h-3.5 w-3.5 animate-spin" /> Expiring…</>
                : <><Trash2 className="h-3.5 w-3.5" /> Expire Stuck Orders</>}
            </button>
            {expireMut.isSuccess && (
              <span className="text-xs text-rose-600 font-medium">
                ✓ {(expireMut.data as any)?.expired ?? 0} expired
              </span>
            )}
            <button type="button" onClick={() => void q.refetch()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50 transition-colors shadow-sm">
              <RefreshCw className={`h-3.5 w-3.5 ${q.isFetching ? "animate-spin" : ""}`} /> Refresh
            </button>
          </div>
        </div>
      </div>

      {/* ── KPI Stats Bar ────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-4">
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-6">
          <StatCard label="Orders Today" value={(stats?.totalToday ?? 0).toLocaleString()} sub={`${(stats?.totalAllTime ?? 0).toLocaleString()} all-time`} accent="border-l-blue-500" icon={Activity} />
          <StatCard label="Filled" value={(stats?.filledToday ?? 0).toLocaleString()} sub={stats?.totalToday ? `${Math.round((stats.filledToday / stats.totalToday) * 100)}% fill rate` : "—"} accent="border-l-emerald-500" icon={CheckCircle2} />
          <StatCard label="Rejected" value={(stats?.rejectedToday ?? 0).toLocaleString()} sub={stats?.totalToday ? `${Math.round((stats.rejectedToday / stats.totalToday) * 100)}% reject rate` : "—"} accent="border-l-rose-500" icon={XCircle} />
          <StatCard label="Partial Fills" value={(stats?.partialToday ?? 0).toLocaleString()} sub="partially executed" accent="border-l-amber-500" icon={BarChart3} />
          <StatCard label="Pending" value={(stats?.pendingToday ?? 0).toLocaleString()} sub="awaiting execution" accent="border-l-sky-500" icon={Clock} />
          <StatCard label="Fill Rate" value={`${fillRate}%`} sub={`${stats?.cancelledToday ?? 0} cancelled today`} accent="border-l-teal-500" icon={Zap} />
        </div>
      </div>

      {/* ── Reject Breakdown ────────────────────────────────────────────────── */}
      {(rejectReasonsQ.data ?? []).length > 0 && (
        <div className="shrink-0 border-b border-slate-200 bg-rose-50/40 px-6 py-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold text-rose-700 mr-1">Rejection causes:</span>
            {(rejectReasonsQ.data ?? []).map(({ reason, count }) => (
              <span key={reason}
                className="inline-flex items-center gap-1 rounded-full border border-rose-200 bg-white px-2.5 py-0.5 text-[11px] font-semibold text-rose-700">
                {reason}
                <span className="ml-1 rounded-full bg-rose-100 px-1.5 py-px text-[10px] font-bold text-rose-800">{count.toLocaleString()}</span>
              </span>
            ))}
          </div>
        </div>
      )}

      {/* ── Filter Bar ───────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-3">
        <div className="flex flex-wrap items-center gap-2">
          <input className={inputCls} placeholder="Symbol…" value={symbol}
            onChange={e => { setSymbol(e.target.value); setPage(0); }} />
          <select className={selectCls} value={strategy} onChange={e => { setStrategy(e.target.value); setPage(0); }}>
            <option value="">All strategies</option>
            {strategyOptions.map((key) => <option key={key} value={key}>{key}</option>)}
          </select>
          <select className={selectCls} value={state} onChange={e => { setState(e.target.value); setPage(0); }}>
            <option value="ALL">All states</option>
            {ORDER_STATES.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
          <select className={selectCls} value={mode} onChange={e => { setMode(e.target.value); setPage(0); }}>
            <option value="ALL">All modes</option>
            <option value="LIVE">LIVE</option>
            <option value="PAPER">PAPER</option>
            <option value="SIMULATED">SIMULATED</option>
          </select>

          {/* Quick state filters */}
          <div className="flex items-center gap-1 ml-1 border-l border-slate-200 pl-3">
            {[
              { label: "Filled", s: "FILLED" },
              { label: "Rejected", s: "REJECTED" },
              { label: "Partial", s: "PARTIALLY_FILLED" },
              { label: "Pending", s: "SUBMITTED" },
            ].map(({ label, s: sv }) => (
              <button key={sv} type="button"
                onClick={() => { setState(sv); setPage(0); }}
                className={`rounded-full border px-2.5 py-0.5 text-[10px] font-semibold transition-colors whitespace-nowrap
                  ${state === sv ? "border-blue-300 bg-blue-50 text-blue-700" : "border-slate-200 bg-slate-50 text-slate-600 hover:bg-slate-100"}`}>
                {label}
              </button>
            ))}
          </div>

          {hasFilters && (
            <button type="button" onClick={clearFilters}
              className="ml-auto flex items-center gap-1 text-xs text-slate-400 hover:text-rose-500 transition-colors">
              <X className="h-3 w-3" /> Clear
            </button>
          )}
          {!hasFilters && (
            <span className="ml-auto text-[11px] text-slate-400">auto-refresh 15s</span>
          )}
        </div>
      </div>

      {/* ── Data Grid ────────────────────────────────────────────────────────── */}
      <div className="flex-1 overflow-auto">
        <table className="w-full min-w-[1220px] text-sm border-collapse">
          <thead className="sticky top-0 z-10 border-b border-slate-200 bg-slate-50">
            <tr>
              {["Time", "User", "Symbol", "Side", "Qty", "Price", "State", "Mode", "Strategy", "Broker", "Reject Reason"].map(h => (
                <th key={h} className="whitespace-nowrap px-4 py-3.5 text-left text-[11px] font-bold uppercase tracking-widest text-slate-500 border-r border-slate-100 last:border-r-0">
                  {h}
                </th>
              ))}
              <th className="w-8 px-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {q.isLoading && (
              <tr><td colSpan={12} className="px-4 py-16 text-center text-slate-400">
                <RefreshCw className="mx-auto mb-2 h-5 w-5 animate-spin opacity-50" />Loading orders…
              </td></tr>
            )}
            {!q.isLoading && rows.length === 0 && (
              <tr><td colSpan={12} className="px-4 py-16 text-center">
                <AlertTriangle className="mx-auto mb-2 h-6 w-6 text-amber-400" />
                <div className="text-sm font-medium text-slate-600">No orders found</div>
                <div className="text-xs text-slate-400 mt-1">Try adjusting the filters above</div>
              </td></tr>
            )}
            {rows.map((r) => (
              <tr key={r.id}
                onClick={() => setSelectedOrder(r)}
                className={`group cursor-pointer transition-colors hover:bg-blue-50/60 ${rowStateBg(r.state)} ${selectedOrder?.id === r.id ? "bg-blue-50 ring-1 ring-inset ring-blue-200" : ""}`}>
                <td className="whitespace-nowrap px-4 py-3 font-mono text-slate-500 text-xs">{fmtTime(r.createdAt)}</td>
                <td className="max-w-[90px] truncate px-4 py-3 font-mono text-slate-500 text-xs" title={r.userId ?? ""}>
                  {r.userId ? r.userId.slice(0, 8) + "…" : "—"}
                </td>
                <td className="px-4 py-3 font-mono font-bold text-slate-900">{r.symbol ?? "—"}</td>
                <td className="px-4 py-3"><SideChip side={r.side} /></td>
                <td className="px-4 py-3 font-mono text-slate-700">{r.quantity ?? "—"}</td>
                <td className="px-4 py-3 font-mono text-slate-700">{r.limitPrice ?? "—"}</td>
                <td className="px-4 py-3"><StateChip state={r.state} /></td>
                <td className="px-4 py-3"><ModeChip mode={r.executionMode} /></td>
                <td className="max-w-[170px] truncate px-4 py-3 text-slate-700 text-xs" title={r.strategyKey ?? ""}>
                  {r.strategyKey ?? "—"}
                </td>
                <td className="px-4 py-3 text-slate-600 text-xs">{r.brokerVendor ?? "—"}</td>
                <td className="max-w-[220px] truncate px-4 py-3 text-rose-600 text-xs" title={r.rejectReason ?? ""}>
                  {r.rejectReason ?? "—"}
                </td>
                <td className="px-3 py-3 text-slate-300 group-hover:text-slate-500 transition-colors">
                  <ChevronRight className="h-3.5 w-3.5" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ── Pagination ───────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-t border-slate-200 bg-white px-6 py-3 flex items-center justify-between">
        <span className="text-xs text-slate-500">
          {total > 0 ? `${page * 50 + 1}–${Math.min((page + 1) * 50, total)} of ${total.toLocaleString()} orders` : "0 results"}
        </span>
        <div className="flex items-center gap-2">
          <button type="button" disabled={page <= 0} onClick={() => setPage(p => Math.max(0, p - 1))}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shadow-sm">
            ← Prev
          </button>
          <span className="text-xs font-medium text-slate-500 tabular-nums px-2">Page {page + 1} / {totalPages}</span>
          <button type="button" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shadow-sm">
            Next →
          </button>
        </div>
      </div>

      {selectedOrder && <OrderDetailDrawer order={selectedOrder} onClose={() => setSelectedOrder(null)} />}
    </div>
  );
}
