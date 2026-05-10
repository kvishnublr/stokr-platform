import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { api } from "../api/client";
import { DataGrid } from "../components/data/DataGrid";

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

  const cols = useMemo<ColumnDef<TradeRow>[]>(
    () => [
      { accessorKey: "createdAt", header: "Time" },
      { accessorKey: "symbol", header: "Symbol" },
      { accessorKey: "quantity", header: "Qty" },
      { accessorKey: "price", header: "Price" },
      { accessorKey: "executionMode", header: "Mode" },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">Trades</h1>
        <p className="mt-1 text-sm text-neutral-400">Ledger fills joined to their originating orders.</p>
      </div>
      <DataGrid columns={cols} data={q.data?.content ?? []} isLoading={q.isLoading} />
      <div className="flex justify-between text-xs text-neutral-500">
        <span>
          Page {(q.data?.page ?? 0) + 1} / {Math.max(1, q.data?.totalPages ?? 1)}
        </span>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={page <= 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            className="rounded-md border border-neutral-800 px-2 py-1 hover:bg-neutral-900 disabled:opacity-40"
          >
            Prev
          </button>
          <button
            type="button"
            disabled={q.data != null && page >= q.data.totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
            className="rounded-md border border-neutral-800 px-2 py-1 hover:bg-neutral-900 disabled:opacity-40"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
