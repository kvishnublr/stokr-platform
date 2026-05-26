import { useQuery } from "@tanstack/react-query";
import { TRADER_EXECUTION_MODE_QUERY_KEY, fetchTraderExecutionMode } from "../lib/traderExecutionMode";
import { motion } from "framer-motion";
import { Download, RefreshCw, ScrollText } from "lucide-react";
import { useMemo, useState } from "react";
import { fmtDateTime } from "../lib/dateUtils";
import { api } from "../api/client";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

type OrderRow = {
  id: string;
  symbol: string;
  side: string;
  state: string;
  executionMode: string | null;
  strategyKey: string | null;
  quantity: string;
  createdAt: string;
  rejectReason: string | null;
};

type PageResponse<T> = {
  content: T[];
  totalPages: number;
  totalElements: number;
  page: number;
  size: number;
};

const STATE_LIGHT: Record<string, string> = {
  FILLED: "border-emerald-200/80 bg-emerald-50 text-emerald-700",
  EXIT_FILLED: "border-emerald-200/80 bg-emerald-50 text-emerald-700",
  PARTIALLY_FILLED: "border-amber-200/80 bg-amber-50 text-amber-700",
  REJECTED: "border-rose-200/80 bg-rose-50 text-rose-700",
  FAILED: "border-red-200/80 bg-red-50 text-red-700",
  CREATED: "border-blue-200/80 bg-blue-50 text-blue-700",
  VALIDATED: "border-sky-200/80 bg-sky-50 text-sky-700",
  SUBMITTED: "border-sky-200/80 bg-sky-50 text-sky-700",
  ACCEPTED: "border-sky-200/80 bg-sky-50 text-sky-700",
  CANCELLED: "border-slate-200 bg-slate-100 text-slate-500",
  EXPIRED: "border-slate-200 bg-slate-100 text-slate-500",
};

const STATE_DARK: Record<string, string> = {
  FILLED: "border-emerald-500/30 bg-emerald-500/15 text-emerald-300",
  EXIT_FILLED: "border-emerald-500/30 bg-emerald-500/15 text-emerald-300",
  PARTIALLY_FILLED: "border-amber-500/30 bg-amber-500/15 text-amber-300",
  REJECTED: "border-rose-500/30 bg-rose-500/15 text-rose-300",
  FAILED: "border-red-500/30 bg-red-500/15 text-red-300",
  CREATED: "border-blue-500/30 bg-blue-500/15 text-blue-300",
  VALIDATED: "border-sky-500/30 bg-sky-500/15 text-sky-300",
  SUBMITTED: "border-sky-500/30 bg-sky-500/15 text-sky-300",
  ACCEPTED: "border-sky-500/30 bg-sky-500/15 text-sky-300",
  CANCELLED: "border-neutral-600 bg-neutral-800/80 text-neutral-400",
  EXPIRED: "border-neutral-600 bg-neutral-800/80 text-neutral-400",
};

function StateBadge({ state, isLight }: { state: string; isLight: boolean }) {
  const s = state.toUpperCase();
  const cfg = isLight ? STATE_LIGHT : STATE_DARK;
  const cls = cfg[s] ?? (isLight ? "border-slate-200 bg-slate-50 text-slate-500" : "border-neutral-700 bg-neutral-800 text-neutral-400");
  return (
    <span className={cn("rounded-md border px-2 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-wide", cls)}>
      {s}
    </span>
  );
}

function SideBadge({ side, isLight }: { side: string; isLight: boolean }) {
  const s = side.toUpperCase();
  if (s === "BUY") {
    return (
      <span className={cn("rounded-md border px-2 py-0.5 text-[10px] font-bold uppercase", isLight ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-emerald-500/30 bg-emerald-500/10 text-emerald-300")}>
        BUY
      </span>
    );
  }
  if (s === "SELL") {
    return (
      <span className={cn("rounded-md border px-2 py-0.5 text-[10px] font-bold uppercase", isLight ? "border-rose-200 bg-rose-50 text-rose-700" : "border-rose-500/30 bg-rose-500/10 text-rose-300")}>
        SELL
      </span>
    );
  }
  return <span className="font-mono text-xs">{side}</span>;
}

function csvEscape(v: string) {
  if (v.includes(",") || v.includes('"') || v.includes("\n")) {
    return `"${v.replace(/"/g, '""')}"`;
  }
  return v;
}

export function OrdersPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [page, setPage] = useState(0);
  const [symbol, setSymbol] = useState("");

  const modeQ = useQuery({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: fetchTraderExecutionMode,
    staleTime: 30_000,
  });
  const executionMode = modeQ.data ?? "PAPER";

  const q = useQuery({
    queryKey: ["oms-orders", page, symbol, executionMode],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(page));
      params.set("size", "25");
      params.set("sort", "createdAt,desc");
      params.set("executionMode", executionMode);
      if (symbol.trim()) params.set("symbol", symbol.trim());
      const res = await api.get(`/api/oms/orders?${params.toString()}`);
      return res.data?.data as PageResponse<OrderRow>;
    },
  });

  const rows = q.data?.content ?? [];

  const rejectedOnPage = useMemo(() => rows.filter((r) => r.state?.toUpperCase() === "REJECTED").length, [rows]);

  function exportCsv() {
    const header = ["createdAt", "symbol", "side", "state", "executionMode", "strategyKey", "quantity", "rejectReason"];
    const lines = [header.join(",")].concat(
      rows.map((r) =>
        [
          r.createdAt,
          r.symbol,
          r.side,
          r.state,
          r.executionMode ?? "",
          r.strategyKey ?? "",
          r.quantity,
          r.rejectReason ?? "",
        ]
          .map(String)
          .map(csvEscape)
          .join(","),
      ),
    );
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "orders.csv";
    a.click();
    URL.revokeObjectURL(url);
  }

  const tableWrap = isLight
    ? "overflow-x-auto rounded-2xl border border-white/60 bg-white/70 shadow-[0_8px_32px_rgba(15,23,42,0.06)] backdrop-blur-xl"
    : "overflow-x-auto rounded-2xl border border-neutral-800/80 bg-neutral-950/50 shadow-[0_8px_32px_rgba(0,0,0,0.35)] backdrop-blur-xl";

  const thCls = isLight
    ? "whitespace-nowrap px-4 py-3 text-left text-[10px] font-bold uppercase tracking-widest text-slate-500"
    : "whitespace-nowrap px-4 py-3 text-left text-[10px] font-bold uppercase tracking-widest text-neutral-500";

  const tdBase = "whitespace-nowrap px-4 py-3 text-xs";

  return (
    <motion.div
      className="space-y-6"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
    >
      <div className="flex flex-wrap items-end justify-between gap-4">
        {!embedded ? (
          <div>
            <h1 className={cn("text-2xl font-semibold tracking-tight", isLight ? "text-slate-900" : "text-white")}>Orders</h1>
            <p className={cn("mt-1 text-sm", isLight ? "text-slate-600" : "text-neutral-400")}>
              OMS history with rejection reasons, server-side pagination, and CSV export.
            </p>
          </div>
        ) : (
          <div />
        )}
        <div className="flex flex-wrap items-center gap-2">
          <input
            placeholder="Filter symbol"
            value={symbol}
            onChange={(e) => {
              setPage(0);
              setSymbol(e.target.value);
            }}
            className={cn(
              "rounded-xl border px-3 py-2 text-sm outline-none transition-shadow focus:ring-2",
              isLight
                ? "border-slate-200/80 bg-white/90 text-slate-900 shadow-sm focus:border-blue-400 focus:ring-blue-100"
                : "border-neutral-800 bg-neutral-950 text-white focus:border-blue-600 focus:ring-blue-900/40",
            )}
          />
          <motion.button
            type="button"
            onClick={() => exportCsv()}
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            className={cn(
              "inline-flex items-center gap-2 rounded-xl border px-3 py-2 text-xs font-semibold transition-colors",
              isLight
                ? "border-slate-200 bg-white text-slate-700 shadow-sm hover:bg-slate-50"
                : "border-neutral-700 text-neutral-100 hover:bg-neutral-900",
            )}
          >
            <Download className="h-3.5 w-3.5" />
            Export CSV
          </motion.button>
        </div>
      </div>

      {!embedded && rows.length > 0 && (
        <motion.div
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.05 }}
          className={cn(
            "flex flex-wrap gap-3 rounded-xl border px-4 py-3 text-xs",
            isLight ? "border-slate-200/80 bg-slate-50/80 text-slate-600" : "border-neutral-800 bg-neutral-900/40 text-neutral-400",
          )}
        >
          <span className="inline-flex items-center gap-1.5 font-medium">
            <ScrollText className="h-3.5 w-3.5 opacity-60" />
            {q.data?.totalElements ?? 0} total orders
          </span>
          {rejectedOnPage > 0 && (
            <span className={isLight ? "text-rose-600" : "text-rose-400"}>
              {rejectedOnPage} rejected on this page
            </span>
          )}
        </motion.div>
      )}

      <motion.div
        className={tableWrap}
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.08, duration: 0.4 }}
      >
        <table className="w-full min-w-[900px] border-collapse text-left">
          <thead className={cn("sticky top-0 z-10 backdrop-blur-md", isLight ? "bg-white/95" : "bg-neutral-950/95")}>
            <tr className={cn("border-b", isLight ? "border-slate-200/80" : "border-neutral-800")}>
              {["Time", "Symbol", "Side", "State", "Mode", "Strategy", "Qty", "Reject reason"].map((h) => (
                <th key={h} className={thCls}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {q.isLoading && (
              <tr>
                <td colSpan={8} className={cn("px-4 py-16 text-center", isLight ? "text-slate-400" : "text-neutral-500")}>
                  <RefreshCw className="mx-auto mb-2 h-5 w-5 animate-spin opacity-50" />
                  Loading orders…
                </td>
              </tr>
            )}
            {!q.isLoading && rows.length === 0 && (
              <tr>
                <td colSpan={8} className={cn("px-4 py-16 text-center text-sm", isLight ? "text-slate-500" : "text-neutral-500")}>
                  No orders yet
                </td>
              </tr>
            )}
            {!q.isLoading &&
              rows.map((r, i) => (
                <motion.tr
                  key={r.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.03 + i * 0.025, duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
                  className={cn(
                    "group border-b transition-colors last:border-b-0",
                    isLight
                      ? i % 2 === 0
                        ? "bg-white/50 hover:bg-blue-50/70"
                        : "bg-slate-50/40 hover:bg-blue-50/70"
                      : i % 2 === 0
                        ? "bg-neutral-950/30 hover:bg-blue-950/30"
                        : "bg-neutral-900/20 hover:bg-blue-950/30",
                    isLight ? "border-slate-100" : "border-neutral-800/60",
                  )}
                >
                  <td className={cn(tdBase, "font-mono", isLight ? "text-slate-600" : "text-neutral-400")}>{fmtDateTime(r.createdAt)}</td>
                  <td className={cn(tdBase, "font-mono font-semibold", isLight ? "text-slate-900" : "text-white")}>{r.symbol}</td>
                  <td className={tdBase}>
                    <SideBadge side={r.side} isLight={isLight} />
                  </td>
                  <td className={tdBase}>
                    <StateBadge state={r.state} isLight={isLight} />
                  </td>
                  <td className={cn(tdBase, isLight ? "text-slate-700" : "text-neutral-300")}>{r.executionMode ?? "—"}</td>
                  <td
                    className={cn("max-w-[160px] truncate px-4 py-3 text-xs", isLight ? "text-slate-700" : "text-neutral-300")}
                    title={r.strategyKey ?? ""}
                  >
                    {r.strategyKey ?? "—"}
                  </td>
                  <td className={cn(tdBase, "font-mono tabular-nums", isLight ? "text-slate-800" : "text-neutral-200")}>{r.quantity}</td>
                  <td
                    className={cn(
                      "max-w-[240px] truncate px-4 py-3 text-xs",
                      r.rejectReason
                        ? isLight
                          ? "text-rose-600"
                          : "text-rose-300"
                        : isLight
                          ? "text-slate-400"
                          : "text-neutral-600",
                    )}
                    title={r.rejectReason ?? undefined}
                  >
                    {r.rejectReason ?? "—"}
                  </td>
                </motion.tr>
              ))}
          </tbody>
        </table>
      </motion.div>

      <motion.div
        className={cn("flex items-center justify-between text-xs", isLight ? "text-slate-500" : "text-neutral-500")}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.15 }}
      >
        <span>
          Page {(q.data?.page ?? 0) + 1} / {Math.max(1, q.data?.totalPages ?? 1)} · {q.data?.totalElements ?? 0} orders
        </span>
        <div className="flex gap-2">
          <motion.button
            type="button"
            disabled={page <= 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            whileHover={page > 0 ? { scale: 1.03 } : undefined}
            whileTap={page > 0 ? { scale: 0.97 } : undefined}
            className={cn(
              "rounded-lg border px-3 py-1.5 disabled:opacity-40",
              isLight ? "border-slate-200 bg-white hover:bg-slate-50" : "border-neutral-800 hover:bg-neutral-900",
            )}
          >
            Prev
          </motion.button>
          <motion.button
            type="button"
            disabled={q.data != null && page >= q.data.totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
            whileHover={q.data != null && page < q.data.totalPages - 1 ? { scale: 1.03 } : undefined}
            whileTap={q.data != null && page < q.data.totalPages - 1 ? { scale: 0.97 } : undefined}
            className={cn(
              "rounded-lg border px-3 py-1.5 disabled:opacity-40",
              isLight ? "border-slate-200 bg-white hover:bg-slate-50" : "border-neutral-800 hover:bg-neutral-900",
            )}
          >
            Next
          </motion.button>
        </div>
      </motion.div>
    </motion.div>
  );
}
