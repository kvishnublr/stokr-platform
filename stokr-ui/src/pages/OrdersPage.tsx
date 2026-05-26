import { useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useMemo, useState } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { fmtDateTime } from "../lib/dateUtils";
import { api } from "../api/client";
import { DataGrid } from "../components/data/DataGrid";
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

export function OrdersPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [page, setPage] = useState(0);
  const [symbol, setSymbol] = useState("");

  const q = useQuery({
    queryKey: ["oms-orders", page, symbol],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(page));
      params.set("size", "25");
      params.set("sort", "createdAt,desc");
      if (symbol.trim()) params.set("symbol", symbol.trim());
      const res = await api.get(`/api/oms/orders?${params.toString()}`);
      return res.data?.data as PageResponse<OrderRow>;
    },
  });

  const cols = useMemo<ColumnDef<OrderRow>[]>(
    () => [
      {
        accessorKey: "createdAt",
        header: "Time",
        cell: ({ getValue }) => (
          <span className={isLight ? "font-mono text-xs text-neutral-600" : "font-mono text-xs text-neutral-300"}>
            {fmtDateTime(getValue() as string)}
          </span>
        ),
      },
      { accessorKey: "symbol", header: "Symbol" },
      { accessorKey: "side", header: "Side" },
      {
        accessorKey: "state",
        header: "State",
        cell: ({ getValue }) => (
          <span className={isLight ? "rounded-md bg-neutral-100 px-2 py-0.5 font-mono text-[10px] uppercase text-neutral-700" : "rounded-md bg-neutral-800 px-2 py-0.5 font-mono text-[10px] uppercase text-neutral-200"}>
            {String(getValue())}
          </span>
        ),
      },
      { accessorKey: "executionMode", header: "Mode" },
      { accessorKey: "strategyKey", header: "Strategy" },
      { accessorKey: "quantity", header: "Qty" },
    ],
    [],
  );

  function exportCsv() {
    const rows = q.data?.content ?? [];
    const header = ["createdAt", "symbol", "side", "state", "executionMode", "strategyKey", "quantity"];
    const lines = [header.join(",")].concat(
      rows.map((r) =>
        [r.createdAt, r.symbol, r.side, r.state, r.executionMode ?? "", r.strategyKey ?? "", r.quantity].join(","),
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

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        {!embedded ? (
          <div>
            <h1 className={isLight ? "text-2xl font-semibold tracking-tight text-neutral-900" : "text-2xl font-semibold tracking-tight text-white"}>Orders</h1>
            <p className={isLight ? "mt-1 text-sm text-neutral-600" : "mt-1 text-sm text-neutral-400"}>OMS history with server-side pagination and CSV export.</p>
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
            className={isLight ? "rounded-lg border border-neutral-200 bg-white px-3 py-1.5 text-sm text-neutral-900 outline-none focus:border-blue-500" : "rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-1.5 text-sm text-white outline-none focus:border-blue-600"}
          />
          <button
            type="button"
            onClick={() => exportCsv()}
            className={isLight ? "inline-flex items-center gap-2 rounded-lg border border-neutral-200 bg-white px-3 py-1.5 text-xs font-semibold text-neutral-700 hover:bg-neutral-50" : "inline-flex items-center gap-2 rounded-lg border border-neutral-700 px-3 py-1.5 text-xs font-semibold text-neutral-100 hover:bg-neutral-900"}
          >
            <Download className="h-3.5 w-3.5" />
            Export CSV
          </button>
        </div>
      </div>

      <DataGrid columns={cols} data={q.data?.content ?? []} isLoading={q.isLoading} emptyLabel="No orders yet" variant={isLight ? "light" : "dark"} />

      <div className="flex items-center justify-between text-xs text-neutral-500">
        <span>
          Page {(q.data?.page ?? 0) + 1} / {Math.max(1, q.data?.totalPages ?? 1)} - {q.data?.totalElements ?? 0} orders
        </span>
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
