import { useQuery } from "@tanstack/react-query";
import { TRADER_EXECUTION_MODE_QUERY_KEY, fetchTraderExecutionMode } from "../lib/traderExecutionMode";
import { useMemo, useState } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { motion } from "framer-motion";
import { api } from "../api/client";
import { DataGrid } from "../components/data/DataGrid";
import { useUiThemeStore } from "../state/uiTheme";
import { fmtDateTime } from "../lib/dateUtils";
import { formatInr, parseMoney } from "../lib/moneyUtils";
import { cn } from "../lib/utils";
import {
  AnimatedKpiCard,
  PremiumPanel,
  StatChip,
  TraderPageShell,
} from "../components/trader/TraderPremium";
import { Activity, ChevronLeft, ChevronRight, Gauge, Timer, TrendingDown } from "lucide-react";

type ExecRow = {
  id: string;
  orderId?: string;
  symbol: string;
  strategyKey?: string | null;
  filledQty: string;
  avgPrice: string | null;
  referencePrice?: string | null;
  latencyMs: number | null;
  slippageBps: string | null;
  spreadBps?: string | null;
  executionMode: string | null;
  orderState?: string | null;
  executionKind?: string | null;
  brokerExecutionId?: string | null;
  fillTime: string | null;
  executionTimestamp?: string | null;
  replaySource?: string | null;
  createdAt: string;
};

type PageResponse<T> = {
  content: T[];
  totalPages: number;
  totalElements?: number;
  page: number;
};

function fmtTime(iso: string | null | undefined) {
  if (!iso) return "—";
  return fmtDateTime(iso);
}

function latencyTone(ms: number | null) {
  if (ms == null) return "";
  if (ms > 2500) return "text-amber-600 dark:text-amber-400";
  if (ms > 1200) return "text-orange-600 dark:text-orange-400";
  return "text-emerald-600 dark:text-emerald-400";
}

export function ExecutionsPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [page, setPage] = useState(0);

  const modeQ = useQuery({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: fetchTraderExecutionMode,
    staleTime: 30_000,
  });
  const executionMode = modeQ.data ?? "PAPER";

  const q = useQuery({
    queryKey: ["oms-execs", page, executionMode],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(page));
      params.set("size", "25");
      params.set("sort", "createdAt,desc");
      params.set("executionMode", executionMode);
      const res = await api.get(`/api/oms/executions?${params.toString()}`);
      return res.data?.data as PageResponse<ExecRow>;
    },
    refetchInterval: 12_000,
  });

  const rows = q.data?.content ?? [];

  const stats = useMemo(() => {
    const latencies = rows.map((r) => r.latencyMs).filter((v): v is number => v != null);
    const slips = rows.map((r) => parseMoney(r.slippageBps)).filter((v): v is number => v != null);
    const avgLatency = latencies.length ? latencies.reduce((a, b) => a + b, 0) / latencies.length : null;
    const avgSlip = slips.length ? slips.reduce((a, b) => a + b, 0) / slips.length : null;
    const filledQty = rows.reduce((s, r) => s + (parseMoney(r.filledQty) ?? 0), 0);
    return { count: rows.length, avgLatency, avgSlip, filledQty };
  }, [rows]);

  const cols = useMemo<ColumnDef<ExecRow>[]>(
    () => [
      {
        id: "fillTime",
        header: "Fill time",
        accessorFn: (r) => r.fillTime ?? r.executionTimestamp ?? r.createdAt,
        cell: ({ getValue }) => <span className="font-mono text-[11px]">{fmtTime(String(getValue() ?? ""))}</span>,
      },
      {
        accessorKey: "symbol",
        header: "Symbol",
        cell: ({ getValue }) => <span className="font-mono font-bold">{String(getValue() ?? "—")}</span>,
      },
      {
        accessorKey: "strategyKey",
        header: "Strategy",
        cell: ({ getValue }) => (
          <span className="max-w-[120px] truncate text-[11px] font-medium text-neutral-500">{String(getValue() ?? "—")}</span>
        ),
      },
      {
        accessorKey: "filledQty",
        header: "Filled",
        cell: ({ getValue }) => <span className="font-mono tabular-nums">{String(getValue() ?? "—")}</span>,
      },
      {
        accessorKey: "avgPrice",
        header: "Avg px",
        cell: ({ getValue }) => {
          const n = parseMoney(getValue());
          return <span className="font-mono tabular-nums">{n != null ? formatInr(n) : "—"}</span>;
        },
      },
      {
        accessorKey: "referencePrice",
        header: "Ref px",
        cell: ({ getValue }) => {
          const n = parseMoney(getValue());
          return <span className="font-mono tabular-nums text-neutral-500">{n != null ? formatInr(n) : "—"}</span>;
        },
      },
      {
        accessorKey: "latencyMs",
        header: "Latency",
        cell: ({ getValue }) => {
          const v = getValue() as number | null;
          if (v == null) return "—";
          return <span className={cn("font-mono font-semibold tabular-nums", latencyTone(v))}>{v} ms</span>;
        },
      },
      {
        accessorKey: "slippageBps",
        header: "Slip bps",
        cell: ({ getValue }) => {
          const n = parseMoney(getValue());
          if (n == null) return "—";
          const tone = Math.abs(n) > 15 ? "text-amber-600" : "text-neutral-600";
          return <span className={cn("font-mono tabular-nums", tone)}>{n.toFixed(2)}</span>;
        },
      },
      {
        accessorKey: "spreadBps",
        header: "Spread bps",
        cell: ({ getValue }) => {
          const n = parseMoney(getValue());
          return <span className="font-mono tabular-nums text-neutral-500">{n != null ? n.toFixed(2) : "—"}</span>;
        },
      },
      {
        accessorKey: "executionKind",
        header: "Kind",
        cell: ({ getValue }) => (
          <span className="rounded-md bg-neutral-500/10 px-2 py-0.5 text-[10px] font-bold uppercase">{String(getValue() ?? "—")}</span>
        ),
      },
      {
        accessorKey: "orderState",
        header: "State",
        cell: ({ getValue }) => {
          const v = String(getValue() ?? "—").toUpperCase();
          const good = v.includes("FILL") || v.includes("COMPLETE");
          return (
            <span className={cn("rounded-md px-2 py-0.5 text-[10px] font-bold uppercase", good ? "bg-emerald-500/15 text-emerald-700" : "bg-neutral-500/10")}>
              {v}
            </span>
          );
        },
      },
      {
        accessorKey: "executionMode",
        header: "Mode",
        cell: ({ getValue }) => {
          const v = String(getValue() ?? "—").toUpperCase();
          return (
            <span className={cn("rounded-md px-2 py-0.5 text-[10px] font-bold", v === "LIVE" ? "bg-rose-500/15 text-rose-700" : "bg-amber-500/15 text-amber-800")}>
              {v}
            </span>
          );
        },
      },
      {
        accessorKey: "brokerExecutionId",
        header: "Broker ID",
        cell: ({ getValue }) => (
          <span className="max-w-[88px] truncate font-mono text-[10px] text-neutral-400" title={String(getValue() ?? "")}>
            {String(getValue() ?? "—")}
          </span>
        ),
      },
    ],
    [],
  );

  const grid = (
    <>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <AnimatedKpiCard label="Fills (page)" loading={q.isLoading} value={String(stats.count)} sublabel={`Mode · ${executionMode}`} icon={Activity} accent="bg-sky-500" />
        <AnimatedKpiCard label="Filled qty" loading={q.isLoading} value={String(stats.filledQty)} accent="bg-indigo-400" />
        <AnimatedKpiCard label="Avg latency" loading={q.isLoading} value={stats.avgLatency != null ? `${Math.round(stats.avgLatency)} ms` : "—"} icon={Timer} accent="bg-violet-400" />
        <AnimatedKpiCard label="Avg slippage" loading={q.isLoading} value={stats.avgSlip != null ? `${stats.avgSlip.toFixed(2)} bps` : "—"} icon={TrendingDown} accent="bg-amber-400" />
      </div>

      <PremiumPanel
        title={`Execution fills · ${executionMode}`}
        action={
          <div className="flex items-center gap-2 text-[11px] text-neutral-500">
            <Gauge className="h-3.5 w-3.5" />
            {q.data?.totalElements != null ? `${q.data.totalElements} total fills` : `Page ${page + 1}`}
          </div>
        }
      >
        <DataGrid columns={cols} data={rows} isLoading={q.isLoading} variant={isLight ? "light" : "dark"} />
        <div className="flex items-center justify-between border-t px-4 py-3 text-xs text-neutral-500">
          <span>
            Page {(q.data?.page ?? 0) + 1}
            {q.data?.totalPages ? ` of ${q.data.totalPages}` : ""}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className={cn(
                "inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 font-semibold disabled:opacity-40",
                isLight ? "border-neutral-200 hover:bg-neutral-50" : "border-neutral-700 hover:bg-neutral-800",
              )}
            >
              <ChevronLeft className="h-3.5 w-3.5" /> Prev
            </button>
            <button
              type="button"
              disabled={q.data != null && page >= q.data.totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
              className={cn(
                "inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 font-semibold disabled:opacity-40",
                isLight ? "border-neutral-200 hover:bg-neutral-50" : "border-neutral-700 hover:bg-neutral-800",
              )}
            >
              Next <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      </PremiumPanel>

      <div className="grid gap-3 sm:grid-cols-3">
        <StatChip label="Best latency" value={stats.avgLatency != null && rows.length ? `${Math.min(...rows.map((r) => r.latencyMs ?? 99999))} ms` : "—"} tone="good" />
        <StatChip label="Worst latency" value={stats.avgLatency != null && rows.length ? `${Math.max(...rows.map((r) => r.latencyMs ?? 0))} ms` : "—"} tone="warn" />
        <StatChip label="Replay source" value={rows.find((r) => r.replaySource)?.replaySource ?? "Live pipeline"} tone="neutral" />
      </div>
    </>
  );

  if (embedded) return <div className="space-y-6">{grid}</div>;

  return (
    <TraderPageShell
      title="Executions"
      subtitle="Fill-level diagnostics with latency, slippage, spread, and broker execution IDs for your selected mode."
    >
      {grid}
    </TraderPageShell>
  );
}
