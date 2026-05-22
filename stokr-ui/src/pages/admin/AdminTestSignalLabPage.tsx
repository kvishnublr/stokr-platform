import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  fetchTestSignalLabOptions,
  fetchTestSignalRunReport,
  fetchTestSignalRuns,
  remediateTestIssue,
  runTestSignalLab,
  type TestSignalLabRequest,
} from "../../api/testSignalLab";

export function AdminTestSignalLabPage() {
  const [selectedRunId, setSelectedRunId] = useState<string>("");
  const [form, setForm] = useState<TestSignalLabRequest>({
    traderUserId: "",
    strategyKey: "",
    symbol: "",
    side: "BUY",
    quantity: 1,
    orderType: "MARKET",
    executionMode: "PAPER",
    triggerType: "INSTANT",
    forceQuantityOne: true,
    dryRunOnly: true,
    skipActualBrokerExecution: true,
    simulateRejection: false,
    simulateTimeout: false,
    simulateStaleWebsocket: false,
    simulateMarginFailure: false,
    simulateBrokerDisconnect: false,
  });

  const options = useQuery({
    queryKey: ["admin-test-signal-lab-options"],
    queryFn: fetchTestSignalLabOptions,
    staleTime: 60_000,
  });

  const runs = useQuery({
    queryKey: ["admin-test-signal-lab-runs"],
    queryFn: () => fetchTestSignalRuns(0, 30),
    refetchInterval: 15_000,
  });

  const report = useQuery({
    queryKey: ["admin-test-signal-lab-run", selectedRunId],
    queryFn: () => fetchTestSignalRunReport(selectedRunId),
    enabled: Boolean(selectedRunId),
    refetchInterval: 10_000,
  });

  const runMutation = useMutation({
    mutationFn: runTestSignalLab,
    onSuccess: (data) => {
      if (data?.testId) setSelectedRunId(String(data.testId));
      void runs.refetch();
    },
  });
  const remediate = useMutation({ mutationFn: remediateTestIssue });

  const brokerOptions = useMemo(() => {
    if (!options.data) return [];
    return options.data.brokerAccounts.filter((b) => b.userId === form.traderUserId);
  }, [options.data, form.traderUserId]);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">Test Signal Lab</h1>
        <p className="text-xs text-muted-foreground">
          Isolated infrastructure testing console for signal → execution → broker → terminal verification.
        </p>
      </div>

      <div className="rounded-xl border border-border bg-card p-4">
        <h2 className="mb-3 text-sm font-semibold">Test Configuration</h2>
        <div className="grid gap-3 md:grid-cols-3">
          <select className="rounded border border-border bg-background px-2 py-2 text-sm" value={form.traderUserId} onChange={(e) => setForm((p) => ({ ...p, traderUserId: e.target.value }))}>
            <option value="">Select trader</option>
            {(options.data?.traders ?? []).map((t) => (
              <option key={t.userId} value={t.userId}>{t.displayName || t.username}</option>
            ))}
          </select>
          <select className="rounded border border-border bg-background px-2 py-2 text-sm" value={form.brokerAccountId ?? ""} onChange={(e) => setForm((p) => ({ ...p, brokerAccountId: e.target.value || undefined }))}>
            <option value="">Auto broker</option>
            {brokerOptions.map((b) => (
              <option key={b.id} value={b.id}>{b.vendorCode} ({b.status})</option>
            ))}
          </select>
          <select className="rounded border border-border bg-background px-2 py-2 text-sm" value={form.strategyKey} onChange={(e) => setForm((p) => ({ ...p, strategyKey: e.target.value }))}>
            <option value="">Select strategy</option>
            {(options.data?.strategies ?? []).map((s) => (
              <option key={s.id} value={s.strategyKey}>{s.displayName}</option>
            ))}
          </select>
          <input className="rounded border border-border bg-background px-2 py-2 text-sm" placeholder="Symbol" value={form.symbol} onChange={(e) => setForm((p) => ({ ...p, symbol: e.target.value.toUpperCase() }))} />
          <select className="rounded border border-border bg-background px-2 py-2 text-sm" value={form.side} onChange={(e) => setForm((p) => ({ ...p, side: e.target.value as "BUY" | "SELL" }))}>
            <option value="BUY">BUY</option>
            <option value="SELL">SELL</option>
          </select>
          <input type="number" className="rounded border border-border bg-background px-2 py-2 text-sm" value={form.quantity ?? 1} onChange={(e) => setForm((p) => ({ ...p, quantity: Number(e.target.value) }))} />
          <select className="rounded border border-border bg-background px-2 py-2 text-sm" value={form.executionMode} onChange={(e) => setForm((p) => ({ ...p, executionMode: e.target.value as any }))}>
            {(options.data?.executionModes ?? ["SIMULATED", "PAPER", "LIVE", "BOTH"]).map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <select className="rounded border border-border bg-background px-2 py-2 text-sm" value={form.triggerType} onChange={(e) => setForm((p) => ({ ...p, triggerType: e.target.value as any }))}>
            {(options.data?.triggerTypes ?? ["INSTANT"]).map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <input type="number" className="rounded border border-border bg-background px-2 py-2 text-sm" placeholder="Price (optional)" value={form.price ?? ""} onChange={(e) => setForm((p) => ({ ...p, price: e.target.value ? Number(e.target.value) : undefined }))} />
          <input type="number" className="rounded border border-border bg-background px-2 py-2 text-sm" placeholder="Auto square-off minutes (optional)" value={form.autoSquareOffMinutes ?? ""} onChange={(e) => setForm((p) => ({ ...p, autoSquareOffMinutes: e.target.value ? Number(e.target.value) : undefined }))} />
        </div>

        <h3 className="mb-2 mt-4 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Safety Options</h3>
        <div className="grid gap-2 md:grid-cols-3">
          {[
            ["forceQuantityOne", "Force quantity = 1"],
            ["dryRunOnly", "Dry run only"],
            ["skipActualBrokerExecution", "Skip actual broker execution"],
            ["simulateRejection", "Simulate rejection"],
            ["simulateTimeout", "Simulate timeout"],
            ["simulateStaleWebsocket", "Simulate stale websocket"],
            ["simulateMarginFailure", "Simulate margin failure"],
            ["simulateBrokerDisconnect", "Simulate broker disconnect"],
          ].map(([k, label]) => (
            <label key={k} className="flex items-center gap-2 text-xs">
              <input
                type="checkbox"
                checked={Boolean((form as any)[k])}
                onChange={(e) => setForm((p) => ({ ...p, [k]: e.target.checked } as any))}
              />
              {label}
            </label>
          ))}
        </div>

        <div className="mt-4 flex items-center gap-2">
          <button
            type="button"
            className="rounded-md bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground disabled:opacity-50"
            disabled={!form.traderUserId || !form.strategyKey || !form.symbol || runMutation.isPending}
            onClick={() => runMutation.mutate(form)}
          >
            {runMutation.isPending ? "Running..." : "Run Test Signal"}
          </button>
          {runMutation.isError && <span className="text-xs text-red-500">Run failed. Check config.</span>}
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div className="rounded-xl border border-border bg-card p-4">
          <h2 className="mb-3 text-sm font-semibold">Recent Test Runs</h2>
          <div className="max-h-[520px] overflow-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-2">Time</th>
                  <th>Strategy</th>
                  <th>Symbol</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {(runs.data?.content ?? []).map((r) => (
                  <tr key={r.id} className="cursor-pointer border-t border-border hover:bg-muted/40" onClick={() => setSelectedRunId(r.id)}>
                    <td className="py-2">{new Date(r.createdAt).toLocaleTimeString()}</td>
                    <td>{r.strategyKey}</td>
                    <td>{r.symbol}</td>
                    <td>{r.finalStatus ?? r.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="rounded-xl border border-border bg-card p-4">
          <h2 className="mb-3 text-sm font-semibold">Execution Report</h2>
          {!report.data ? (
            <p className="text-xs text-muted-foreground">Select a run to view detailed verification report.</p>
          ) : (
            <div className="space-y-3 text-xs">
              <div className="rounded border border-border p-2">
                <div>Test ID: {report.data.testId}</div>
                <div>Final Status: <span className="font-semibold">{report.data.finalStatus}</span></div>
                <div>Total Latency: {report.data.totalLatencyMs ?? 0} ms</div>
              </div>
              <div>
                <div className="mb-1 font-semibold">Checklist</div>
                <div className="space-y-1">
                  {(report.data.checks ?? []).map((c: any) => (
                    <div key={c.key} className="rounded border border-border p-2">
                      <div className="font-medium">{c.label}: {c.status}</div>
                      <div className="text-muted-foreground">{c.message}</div>
                      {c.suggestedAction && <div className="text-amber-500">Action: {c.suggestedAction}</div>}
                      {c.actionCode && (
                        <button
                          type="button"
                          className="mt-2 rounded border border-border px-2 py-1 text-[11px] hover:bg-muted/40"
                          onClick={() => remediate.mutate({ actionCode: c.actionCode, traderUserId: form.traderUserId })}
                        >
                          Run Fix ({c.actionCode})
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
              <div>
                <div className="mb-1 font-semibold">Pipeline Timeline</div>
                <div className="max-h-48 space-y-1 overflow-auto">
                  {(report.data.timeline ?? []).map((t: any, idx: number) => (
                    <div key={idx} className="rounded border border-border p-2">
                      <div>{t.stage}</div>
                      <div className="text-muted-foreground">{t.at ? new Date(t.at).toLocaleString() : "-"} {t.detail ? `• ${t.detail}` : ""}</div>
                    </div>
                  ))}
                </div>
              </div>
              {remediate.isSuccess && (
                <pre className="max-h-40 overflow-auto rounded border border-border bg-background p-2 text-[10px] text-muted-foreground">
                  {JSON.stringify(remediate.data, null, 2)}
                </pre>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
