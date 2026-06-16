import { useQuery } from "@tanstack/react-query";
import { fetchTestSignalRuns, fetchTestSignalTelemetry } from "../../api/testSignalLab";
import { fmtDateTime } from "../../lib/dateUtils";

export function AdminTestExecutionMonitorPage() {
  const runs = useQuery({
    queryKey: ["admin-test-execution-monitor"],
    queryFn: () => fetchTestSignalRuns(0, 100),
    refetchInterval: 10_000,
  });
  const telemetry = useQuery({
    queryKey: ["admin-test-execution-telemetry"],
    queryFn: fetchTestSignalTelemetry,
    refetchInterval: 10_000,
  });

  return (
    <div className="space-y-3">
      <h1 className="text-xl font-semibold">Test Execution Monitor</h1>
      <p className="text-xs text-muted-foreground">
        Live feed of recent test execution runs and end-to-end outcomes.
      </p>
      <div className="grid gap-3 md:grid-cols-3">
        <Metric label="Total Runs" value={telemetry.data?.totalRuns ?? 0} />
        <Metric label="Success Rate %" value={(telemetry.data?.successRatePct ?? 0).toFixed?.(2) ?? telemetry.data?.successRatePct ?? 0} />
        <Metric label="P95 Latency (ms)" value={(telemetry.data?.p95LatencyMs ?? 0).toFixed?.(0) ?? telemetry.data?.p95LatencyMs ?? 0} />
      </div>
      <div className="rounded-xl border border-border bg-card p-3">
        <table className="w-full text-left text-xs">
          <thead className="text-muted-foreground">
            <tr>
              <th className="py-2">Created</th>
              <th>Trader</th>
              <th>Strategy</th>
              <th>Symbol</th>
              <th>Mode</th>
              <th>Status</th>
              <th>Square-off</th>
            </tr>
          </thead>
          <tbody>
            {(runs.data?.content ?? []).map((r) => (
              <tr key={r.id} className="border-t border-border">
                <td className="py-2">{fmtDateTime(r.createdAt)}</td>
                <td>{r.traderUserId}</td>
                <td>{r.strategyKey}</td>
                <td>{r.symbol}</td>
                <td>{r.executionMode}</td>
                <td>{r.finalStatus ?? r.status}</td>
                <td>{r.squareOffStatus ?? "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-xl border border-border bg-card p-3">
      <div className="text-[11px] text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold">{value}</div>
    </div>
  );
}
