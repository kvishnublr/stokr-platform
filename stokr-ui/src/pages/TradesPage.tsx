import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { motion } from "framer-motion";
import { Activity, ChevronLeft, ChevronRight, Layers, Receipt } from "lucide-react";
import { api } from "../api/client";
import { DataGrid } from "../components/data/DataGrid";
import { useUiThemeStore } from "../state/uiTheme";
import { fmtDateTime } from "../lib/dateUtils";
import { cn } from "../lib/utils";
import {
  AnimatedKpiCard,
  PremiumPanel,
  TraderPageShell,
} from "../components/trader/TraderPremium";

type TradeRow = {
  id: string;
  symbol: string;
  quantity: string;
  price: string;
  executionMode: string | null;
  createdAt: string;
};

type PageResponse<T> = {
  content: T[];
  totalPages: number;
  totalElements: number;
  page: number;
};

export function TradesPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [page, setPage] = useState(0);

  const q = useQuery({
    queryKey: ["oms-trades", page],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(page));
      params.set("size", "25");
      params.set("sort", "createdAt,desc");
      const res = await api.get(`/api/oms/trades?${params.toString()}`);
      return res.data?.data as PageResponse<TradeRow>;
    },
  });

  const rows = q.data?.content ?? [];
  const stats = useMemo(() => {
    const filledQty = rows.reduce((sum, r) => sum + (Number(r.quantity) || 0), 0);
    const liveCount = rows.filter((r) => String(r.executionMode ?? "").toUpperCase() === "LIVE").length;
    const symbols = new Set(rows.map((r) => r.symbol)).size;
    return { count: rows.length, filledQty, liveCount, symbols };
  }, [rows]);

  const cols = useMemo<ColumnDef<TradeRow>[]>(
    () => [
      {
        accessorKey: "createdAt",
        header: "Time",
        cell: ({ getValue }) => fmtDateTime(String(getValue() ?? "")),
      },
      { accessorKey: "symbol", header: "Symbol" },
      { accessorKey: "quantity", header: "Qty" },
      { accessorKey: "price", header: "Price" },
      {
        accessorKey: "executionMode",
        header: "Mode",
        cell: ({ getValue }) => {
          const v = String(getValue() ?? "—").toUpperCase();
          return (
            <span
              className={cn(
                "rounded-md px-2 py-0.5 text-[10px] font-bold",
                v === "LIVE" ? "bg-rose-500/15 text-rose-700" : "bg-amber-500/15 text-amber-800",
              )}
            >
              {v}
            </span>
          );
        },
      },
    ],
    [],
  );

  return (
    <TraderPageShell
      title="Trades"
      subtitle="Ledger fills joined to their originating orders."
    >
      <motion.div initial="hidden" animate="show" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <AnimatedKpiCard label="Fills (page)" loading={q.isLoading} value={String(stats.count)} sublabel="Current page" icon={Receipt} accent="bg-sky-500" index={0} />
        <AnimatedKpiCard label="Filled qty" loading={q.isLoading} value={String(stats.filledQty)} icon={Layers} accent="bg-indigo-400" index={1} />
        <AnimatedKpiCard label="Live fills" loading={q.isLoading} value={String(stats.liveCount)} sublabel="On this page" icon={Activity} accent="bg-rose-400" index={2} />
        <AnimatedKpiCard label="Symbols" loading={q.isLoading} value={String(stats.symbols)} accent="bg-amber-400" index={3} />
      </motion.div>

      <PremiumPanel
        title="Trade ledger"
        action={
          <span className="text-[11px] text-neutral-500">
            {q.data?.totalElements != null ? `${q.data.totalElements} total fills` : `Page ${page + 1}`}
          </span>
        }
      >
        <DataGrid columns={cols} data={rows} isLoading={q.isLoading} variant={isLight ? "light" : "dark"} />
        <div className="flex flex-col gap-2 border-t px-4 py-3 sm:flex-row sm:items-center sm:justify-between text-xs text-neutral-500">
          <span className="text-xs">
            Page {(q.data?.page ?? 0) + 1}
            {q.data?.totalPages ? ` of ${q.data.totalPages}` : ""}
          </span>
          <div className="flex gap-2 w-full sm:w-auto">
            <button
              type="button"
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className={cn(
                "flex-1 sm:flex-none inline-flex items-center justify-center gap-1 rounded-lg border px-3 py-1.5 text-xs sm:text-xs font-semibold disabled:opacity-40",
                isLight ? "border-neutral-200 hover:bg-neutral-50" : "border-neutral-700 hover:bg-neutral-800",
              )}
            >
              <ChevronLeft className="h-3.5 w-3.5" /> <span className="sm:inline">Prev</span>
            </button>
            <button
              type="button"
              disabled={q.data != null && page >= q.data.totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
              className={cn(
                "flex-1 sm:flex-none inline-flex items-center justify-center gap-1 rounded-lg border px-3 py-1.5 text-xs sm:text-xs font-semibold disabled:opacity-40",
                isLight ? "border-neutral-200 hover:bg-neutral-50" : "border-neutral-700 hover:bg-neutral-800",
              )}
            >
              <span className="sm:inline">Next</span> <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      </PremiumPanel>
    </TraderPageShell>
  );
}
