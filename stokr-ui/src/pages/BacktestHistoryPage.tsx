import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { listRuns } from "../api/backtest";
import { parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

export function BacktestHistoryPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const q = useQuery({
    queryKey: ["backtest-runs"],
    queryFn: () => listRuns(0, 40),
  });

  if (q.isLoading) {
    return <div className={cn("text-sm", isLight ? "text-[#64748B]" : "text-neutral-400")}>Loading runs...</div>;
  }
  if (q.isError) {
    return <div className="text-sm text-red-500">{parseAxiosMessage(q.error)}</div>;
  }

  const rows = q.data?.page.content ?? [];

  return (
    <div className="space-y-4">
      <div className={cn("text-xs", isLight ? "text-[#64748B]" : "text-neutral-500")}>
        {q.data?.correlationId ? <>Last request correlation: {q.data.correlationId}</> : null}
      </div>

      <div
        className={cn(
          "overflow-hidden rounded-2xl border shadow-sm",
          isLight ? "border-slate-900/[0.08] bg-white" : "border-neutral-800 bg-neutral-950/60",
        )}
      >
        <table className="w-full text-left text-sm">
          <thead
            className={cn(
              "border-b text-xs uppercase tracking-wide",
              isLight
                ? "border-slate-900/[0.08] bg-[#F8FAFC] text-[#64748B]"
                : "border-neutral-800 bg-neutral-950 text-neutral-500",
            )}
          >
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
                <td
                  colSpan={6}
                  className={cn("px-4 py-8 text-center", isLight ? "text-[#64748B]" : "text-neutral-500")}
                >
                  No runs yet - launch a replay from the Launch tab.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr
                  key={r.id}
                  className={cn(
                    "border-b transition-colors last:border-b-0",
                    isLight
                      ? "border-slate-900/[0.08] hover:bg-[#F8FAFC]"
                      : "border-neutral-900 hover:bg-neutral-900/40",
                  )}
                >
                  <td
                    className={cn(
                      "px-4 py-3 font-mono text-xs",
                      isLight ? "text-[#475569]" : "text-neutral-300",
                    )}
                  >
                    {new Date(r.createdAt).toLocaleString()}
                  </td>
                  <td className={cn("px-4 py-3", isLight ? "text-[#0F172A]" : "text-neutral-200")}>{r.strategyKey}</td>
                  <td
                    className={cn(
                      "px-4 py-3 font-mono",
                      isLight ? "text-[#475569]" : "text-neutral-300",
                    )}
                  >
                    {r.symbol}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={cn(
                        "inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold",
                        r.status === "COMPLETED"
                          ? isLight
                            ? "bg-emerald-50 text-emerald-800 ring-1 ring-emerald-600/15"
                            : "text-emerald-400"
                          : r.status === "FAILED"
                            ? isLight
                              ? "bg-red-50 text-red-800 ring-1 ring-red-600/15"
                              : "text-red-400"
                            : r.status === "RUNNING"
                              ? isLight
                                ? "border border-sky-200 bg-sky-50 text-sky-900"
                                : "border border-sky-500/30 bg-sky-950/40 text-sky-200"
                              : isLight
                                ? "bg-amber-50 text-amber-950 ring-1 ring-amber-600/20"
                                : "text-amber-300",
                      )}
                    >
                      {r.status}
                    </span>
                  </td>
                  <td
                    className={cn(
                      "px-4 py-3 font-mono text-[11px]",
                      isLight ? "text-[#64748B]" : "text-neutral-400",
                    )}
                  >
                    {r.replayHashPreview ?? "-"}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Link
                      to={`/backtests/${r.id}`}
                      className={cn(
                        "rounded-lg border px-3 py-1 text-xs transition",
                        isLight
                          ? "border-slate-900/[0.12] text-[#0F172A] hover:bg-[#F8FAFC]"
                          : "border-neutral-700 text-neutral-200 hover:bg-neutral-900",
                      )}
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
