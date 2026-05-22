import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { fetchTestSignalRunReport, fetchTestSignalRuns, remediateTestIssue } from "../../api/testSignalLab";

export function AdminFailureAnalysisConsolePage() {
  const [selectedRunId, setSelectedRunId] = useState<string>("");
  const runs = useQuery({
    queryKey: ["admin-failure-analysis-runs"],
    queryFn: () => fetchTestSignalRuns(0, 100),
    refetchInterval: 15_000,
  });
  const report = useQuery({
    queryKey: ["admin-failure-analysis-report", selectedRunId],
    queryFn: () => fetchTestSignalRunReport(selectedRunId),
    enabled: Boolean(selectedRunId),
  });
  const remediate = useMutation({
    mutationFn: remediateTestIssue,
  });

  return (
    <div className="space-y-3">
      <h1 className="text-xl font-semibold">Failure Analysis Console</h1>
      <p className="text-xs text-muted-foreground">
        Plain-English failure diagnostics and operator recovery suggestions from test runs.
      </p>
      <div className="grid gap-3 lg:grid-cols-2">
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="mb-2 text-sm font-semibold">Recent Runs</div>
          <div className="space-y-1 text-xs">
            {(runs.data?.content ?? []).map((r) => (
              <button
                key={r.id}
                type="button"
                className="w-full rounded border border-border px-2 py-2 text-left hover:bg-muted/40"
                onClick={() => setSelectedRunId(r.id)}
              >
                {r.strategyKey} • {r.symbol} • {r.finalStatus ?? r.status}
              </button>
            ))}
          </div>
        </div>
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="mb-2 text-sm font-semibold">Diagnostics</div>
          {!report.data ? (
            <div className="text-xs text-muted-foreground">Pick a run to inspect diagnostics.</div>
          ) : (
            <div className="space-y-2 text-xs">
              {(report.data.checks ?? []).filter((c: any) => c.status !== "SUCCESS").map((c: any) => (
                <div key={c.key} className="rounded border border-border p-2">
                  <div className="font-medium">{c.label} — {c.status}</div>
                  <div className="text-muted-foreground">{c.message}</div>
                  {c.suggestedAction && <div className="text-amber-500">Suggested: {c.suggestedAction}</div>}
                  {c.actionCode && (
                    <button
                      type="button"
                      className="mt-2 rounded border border-border px-2 py-1 text-[11px] hover:bg-muted/40"
                      onClick={() => remediate.mutate({ actionCode: c.actionCode })}
                    >
                      Run Action ({c.actionCode})
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
          {remediate.isSuccess && (
            <pre className="mt-3 max-h-36 overflow-auto rounded border border-border bg-background p-2 text-[10px] text-muted-foreground">
              {JSON.stringify(remediate.data, null, 2)}
            </pre>
          )}
        </div>
      </div>
    </div>
  );
}
