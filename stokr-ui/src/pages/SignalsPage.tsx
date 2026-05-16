import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { api } from "../api/client";
import { DataGrid } from "../components/data/DataGrid";
import { useUiThemeStore } from "../state/uiTheme";

type SignalRow = {
  id: string;
  createdAt: string | null;
  symbol: string | null;
  signalType: string | null;
  strategyName: string | null;
  reason: string | null;
  suggestedQty: string | null;
  confidenceScore: string | null;
};

export function SignalsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [symbol, setSymbol] = useState("");

  const q = useQuery({
    queryKey: ["trader-signals-feed"],
    queryFn: async () => {
      const res = await api.get("/api/trader/strategy-feed?limit=500");
      return (Array.isArray(res.data?.data) ? res.data.data : []) as SignalRow[];
    },
    refetchInterval: 15_000,
  });

  const rows = useMemo(() => {
    const s = symbol.trim().toUpperCase();
    if (!s) return q.data ?? [];
    return (q.data ?? []).filter((r) => String(r.symbol ?? "").toUpperCase().includes(s));
  }, [q.data, symbol]);

  const cols = useMemo<ColumnDef<SignalRow>[]>(
    () => [
      { accessorKey: "createdAt", header: "Time" },
      { accessorKey: "strategyName", header: "Strategy" },
      { accessorKey: "symbol", header: "Symbol" },
      { accessorKey: "signalType", header: "Side" },
      { accessorKey: "suggestedQty", header: "Qty" },
      { accessorKey: "confidenceScore", header: "Confidence" },
      { accessorKey: "reason", header: "Rationale" },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className={isLight ? "text-2xl font-semibold tracking-tight text-neutral-900" : "text-2xl font-semibold tracking-tight text-white"}>
            Signals
          </h1>
          <p className={isLight ? "mt-1 text-sm text-neutral-600" : "mt-1 text-sm text-neutral-400"}>
            Live trader signal feed by strategy and symbol.
          </p>
        </div>
        <input
          placeholder="Filter symbol"
          value={symbol}
          onChange={(e) => setSymbol(e.target.value)}
          className={isLight ? "rounded-lg border border-neutral-200 bg-white px-3 py-1.5 text-sm text-neutral-900 outline-none focus:border-blue-500" : "rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-1.5 text-sm text-white outline-none focus:border-blue-600"}
        />
      </div>
      <DataGrid columns={cols} data={rows} isLoading={q.isLoading} emptyLabel="No signals yet" variant={isLight ? "light" : "dark"} />
    </div>
  );
}
