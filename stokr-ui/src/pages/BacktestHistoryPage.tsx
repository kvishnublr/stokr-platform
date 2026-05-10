import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { listRuns } from "../api/backtest";
import { parseAxiosMessage } from "../api/client";

export function BacktestHistoryPage() {
  const q = useQuery({
    queryKey: ["backtest-runs"],
    queryFn: () => listRuns(0, 40),
  });

  if (q.isLoading) {
    return <div className="text-sm text-neutral-400">Loading runs…</div>;
  }
  if (q.isError) {
    return <div className="text-sm text-red-400">{parseAxiosMessage(q.error)}</div>;
  }

  const rows = q.data?.page.content ?? [];

  return (
    <div className="space-y-4">
      <div className="text-xs text-neutral-500">
        {q.data?.correlationId ? <>Last request correlation: {q.data.correlationId}</> : null}
      </div>

      <div className="overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-950/60">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-800 bg-neutral-950 text-xs uppercase tracking-wide text-neutral-500">
            <tr>
              <th className="px-4 py-3">Created</th>
              <th className="px-4 py-3">Strategy</th>
              <th className="px-4 py-3">Symbol</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Replay hash</th>
              <th className="px-4 py-3 text-right"> </th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-neutral-500">
                  No runs yet — launch a replay from the Launch tab.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.id} className="border-b border-neutral-900 hover:bg-neutral-900/40">
                  <td className="px-4 py-3 font-mono text-xs text-neutral-300">
                    {new Date(r.createdAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-neutral-200">{r.strategyKey}</td>
                  <td className="px-4 py-3 font-mono text-neutral-300">{r.symbol}</td>
                  <td className="px-4 py-3">
                    <span
                      className={
                        r.status === "COMPLETED"
                          ? "text-emerald-400"
                          : r.status === "FAILED"
                            ? "text-red-400"
                            : "text-amber-300"
                      }
                    >
                      {r.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 font-mono text-[11px] text-neutral-400">
                    {r.replayHashPreview ?? "—"}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Link
                      to={`/backtests/${r.id}`}
                      className="rounded-lg border border-neutral-700 px-3 py-1 text-xs text-neutral-200 hover:bg-neutral-900"
                    >
                      Open
                    </Link>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
