import { useQuery } from "@tanstack/react-query";
import { fetchStrategyLeaderboard } from "../api/research";
import { parseAxiosMessage } from "../api/client";

function fmt(v: string | number | undefined) {
  if (v === undefined || v === null) return "-";
  const n = typeof v === "number" ? v : Number(v);
  if (!Number.isFinite(n)) return String(v);
  return n.toFixed(4);
}

export function ResearchLeaderboardPage() {
  const q = useQuery({
    queryKey: ["strategy-leaderboard"],
    queryFn: () => fetchStrategyLeaderboard(),
  });

  if (q.isLoading) return <div className="text-sm text-neutral-400">Loading leaderboard...</div>;
  if (q.isError) return <div className="text-sm text-red-400">{parseAxiosMessage(q.error)}</div>;

  const rows = q.data?.rows ?? [];

  return (
    <div className="space-y-4">
      {q.data?.correlationId ? (
        <div className="font-mono text-xs text-neutral-500">Correlation {q.data.correlationId}</div>
      ) : null}
      <div className="overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-950/60">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-800 text-xs uppercase tracking-wide text-neutral-500">
            <tr>
              <th className="px-4 py-3">Strategy</th>
              <th className="px-4 py-3">Avg Sharpe</th>
              <th className="px-4 py-3">Avg win rate</th>
              <th className="px-4 py-3">Avg max DD</th>
              <th className="px-4 py-3">Runs</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-neutral-500">
                  No materialized backtest metrics yet - complete a replay first.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.strategyKey} className="border-b border-neutral-900 font-mono text-xs">
                  <td className="px-4 py-3 text-neutral-100">{r.strategyKey}</td>
                  <td className="px-4 py-3 text-emerald-300">{fmt(r.avgSharpeRatio)}</td>
                  <td className="px-4 py-3">{fmt(r.avgWinRate)}</td>
                  <td className="px-4 py-3 text-amber-200">{fmt(r.avgMaxDrawdown)}</td>
                  <td className="px-4 py-3 text-neutral-400">{r.sampleRuns}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
