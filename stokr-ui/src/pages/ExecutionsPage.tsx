import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { api } from "../api/client";
import { DataGrid } from "../components/data/DataGrid";
import { useUiThemeStore } from "../state/uiTheme";

type ExecRow = {
  id: string;
  symbol: string;
  filledQty: string;
  avgPrice: string | null;
  latencyMs: number | null;
  slippageBps: string | null;
  executionMode: string | null;
  fillTime: string | null;
  createdAt: string;
};

type PageResponse<T> = {
  content: T[];
  totalPages: number;
  page: number;
};

export function ExecutionsPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [page, setPage] = useState(0);

  const q = useQuery({
    queryKey: ["oms-execs", page],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(page));
      params.set("size", "25");
      params.set("sort", "createdAt,desc");
      const res = await api.get(`/api/oms/executions?${params.toString()}`);
      return res.data?.data as PageResponse<ExecRow>;
    },
  });

  const cols = useMemo<ColumnDef<ExecRow>[]>(
    () => [
      { accessorKey: "createdAt", header: "Time" },
      { accessorKey: "symbol", header: "Symbol" },
      { accessorKey: "filledQty", header: "Filled" },
      { accessorKey: "avgPrice", header: "Avg px" },
      {
        accessorKey: "latencyMs",
        header: "Latency ms",
        cell: ({ getValue }) => {
          const v = getValue() as number | null;
          if (v == null) return "—";
          const tone = v > 2500 ? "text-amber-600" : "text-emerald-600";
          return <span className={tone}>{v}</span>;
        },
      },
      { accessorKey: "slippageBps", header: "Slip bps" },
      { accessorKey: "executionMode", header: "Mode" },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      {!embedded ? (
        <div>
          <h1 className={isLight ? "text-2xl font-semibold tracking-tight text-neutral-900" : "text-2xl font-semibold tracking-tight text-white"}>Executions</h1>
          <p className={isLight ? "mt-1 text-sm text-neutral-600" : "mt-1 text-sm text-neutral-400"}>
            Fill legs with latency and slippage diagnostics (simulated + live pipelines).
          </p>
        </div>
      ) : null}
      <DataGrid columns={cols} data={q.data?.content ?? []} isLoading={q.isLoading} variant={isLight ? "light" : "dark"} />
      <div className="flex justify-between text-xs text-neutral-500">
        <span>Page {(q.data?.page ?? 0) + 1}</span>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={page <= 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            className={isLight ? "rounded-md border border-neutral-200 px-2 py-1 hover:bg-neutral-50 disabled:opacity-40" : "rounded-md border border-neutral-800 px-2 py-1 hover:bg-neutral-900 disabled:opacity-40"}
          >
            Prev
          </button>
          <button
            type="button"
            disabled={q.data != null && page >= q.data.totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
            className={isLight ? "rounded-md border border-neutral-200 px-2 py-1 hover:bg-neutral-50 disabled:opacity-40" : "rounded-md border border-neutral-800 px-2 py-1 hover:bg-neutral-900 disabled:opacity-40"}
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
