import { useQuery } from "@tanstack/react-query";
import { ActivitySquare, Wallet } from "lucide-react";
import { api } from "../api/client";

type Dash = {
  overview: {
    realizedPnl: string;
    unrealizedPnl: string;
    openPositionCount: number;
  };
};

/** Paper / simulated exposure uses the same portfolio APIs - isolate accounts via broker configuration in production. */
export function PaperTradingPage() {
  const q = useQuery({
    queryKey: ["paper-portfolio"],
    queryFn: async () => {
      const res = await api.get("/api/portfolio/dashboard?equityPoints=60");
      return res.data?.data as Dash;
    },
  });

  const o = q.data?.overview;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">Paper trading</h1>
        <p className="mt-2 max-w-2xl text-sm text-neutral-400">
          Simulated fills and latency-aware execution share portfolio endpoints with live - verify execution mode on orders
          before promoting strategies.
        </p>
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">
            <Wallet className="h-4 w-4" />
            Realized PnL
          </div>
          <div className="mt-3 font-mono text-2xl text-white">{o?.realizedPnl ?? "-"}</div>
        </div>
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
          <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500">Unrealized</div>
          <div className="mt-3 font-mono text-2xl text-white">{o?.unrealizedPnl ?? "-"}</div>
        </div>
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">
            <ActivitySquare className="h-4 w-4" />
            Open positions
          </div>
          <div className="mt-3 font-mono text-2xl text-white">{o?.openPositionCount ?? "-"}</div>
        </div>
      </div>

      <div className="rounded-xl border border-amber-900/40 bg-amber-950/20 px-4 py-3 text-sm text-amber-100">
        For emergency controls (pause strategies, kill switch) use{" "}
        <a className="underline" href="/admin/ops">
          Admin to Operations
        </a>{" "}
        or runtime APIs.
      </div>
    </div>
  );
}
