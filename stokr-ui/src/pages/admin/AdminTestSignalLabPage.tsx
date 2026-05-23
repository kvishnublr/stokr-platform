import { useMemo, useState, useRef, useEffect } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  fetchTestSignalLabOptions,
  fetchTestSignalRunReport,
  fetchTestSignalRuns,
  remediateTestIssue,
  runTestSignalLab,
  runTestSignalPreflight,
  type TestSignalLabRequest,
} from "../../api/testSignalLab";

function fieldClassName() {
  return "w-full rounded-lg border border-border bg-background px-3 py-2.5 text-sm shadow-sm outline-none transition focus:border-primary focus:ring-1 focus:ring-primary";
}

function sectionTitleClassName() {
  return "text-[11px] font-bold uppercase tracking-[0.08em] text-muted-foreground";
}

function statusPillClassName(status?: string | null) {
  const key = (status ?? "").toUpperCase();
  if (key.includes("SUCCESS") || key.includes("PASSED") || key.includes("FILLED") || key.includes("DONE")) {
    return "border-emerald-300 bg-emerald-50 text-emerald-700";
  }
  if (key.includes("WARN") || key.includes("PENDING") || key.includes("RUNNING")) {
    return "border-amber-300 bg-amber-50 text-amber-700";
  }
  if (key.includes("FAIL") || key.includes("ERROR") || key.includes("REJECT")) {
    return "border-rose-300 bg-rose-50 text-rose-700";
  }
  return "border-border bg-muted/40 text-foreground";
}

type StrategyOption = { id: string; strategyKey: string; displayName: string };

function StrategySearchDropdown({
  strategies,
  value,
  onChange,
}: {
  strategies: StrategyOption[];
  value: string;
  onChange: (key: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const containerRef = useRef<HTMLDivElement>(null);

  const selected = strategies.find((s) => s.strategyKey === value);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return strategies;
    return strategies.filter(
      (s) =>
        s.displayName.toLowerCase().includes(q) ||
        s.strategyKey.toLowerCase().includes(q),
    );
  }, [strategies, search]);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
        setSearch("");
      }
    }
    if (open) document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, [open]);

  return (
    <div ref={containerRef} className="relative w-full">
      <button
        type="button"
        className={`${fieldClassName()} flex items-center justify-between text-left`}
        onClick={() => {
          setOpen((o) => !o);
          setSearch("");
        }}
      >
        <span className={selected ? "text-foreground" : "text-muted-foreground"}>
          {selected ? selected.displayName : "Select strategy…"}
        </span>
        <svg className="ml-2 h-4 w-4 shrink-0 text-muted-foreground" viewBox="0 0 20 20" fill="currentColor">
          <path fillRule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clipRule="evenodd" />
        </svg>
      </button>

      {open && (
        <div className="absolute z-50 mt-1 w-full overflow-hidden rounded-lg border border-border bg-background shadow-lg">
          <div className="border-b border-border p-2">
            <input
              autoFocus
              className="w-full rounded-md border border-border bg-muted/30 px-2.5 py-1.5 text-sm outline-none focus:border-primary"
              placeholder="Search strategy…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") { setOpen(false); setSearch(""); }
                if (e.key === "Enter" && filtered.length === 1) {
                  onChange(filtered[0].strategyKey);
                  setOpen(false);
                  setSearch("");
                }
              }}
            />
          </div>
          <ul className="max-h-56 overflow-y-auto py-1">
            {filtered.length === 0 && (
              <li className="px-3 py-2 text-sm text-muted-foreground">No strategies match</li>
            )}
            {filtered.map((s) => (
              <li key={s.id}>
                <button
                  type="button"
                  className={`w-full px-3 py-2 text-left text-sm hover:bg-primary/10 ${
                    s.strategyKey === value ? "bg-primary/10 font-semibold text-primary" : "text-foreground"
                  }`}
                  onClick={() => {
                    onChange(s.strategyKey);
                    setOpen(false);
                    setSearch("");
                  }}
                >
                  <div className="font-medium">{s.displayName}</div>
                  <div className="text-[11px] text-muted-foreground font-mono">{s.strategyKey}</div>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

const STORAGE_KEY = "testSignalLabDefaults";

const DEFAULT_FORM: TestSignalLabRequest = {
  traderUserId: "",
  strategyKey: "",
  symbol: "SBIN",
  side: "BUY",
  quantity: 1,
  orderType: "MARKET",
  executionMode: "LIVE",
  triggerType: "INSTANT",
  forceQuantityOne: true,
  dryRunOnly: false,
  skipActualBrokerExecution: false,
  simulateRejection: false,
  simulateTimeout: false,
  simulateStaleWebsocket: false,
  simulateMarginFailure: false,
  simulateBrokerDisconnect: false,
};

export function AdminTestSignalLabPage() {
  const [selectedRunId, setSelectedRunId] = useState<string>("");

  const [form, setForm] = useState<TestSignalLabRequest>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      return stored ? { ...DEFAULT_FORM, ...JSON.parse(stored) } : DEFAULT_FORM;
    } catch {
      return DEFAULT_FORM;
    }
  });

  // Persist form to localStorage whenever it changes
  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(form));
  }, [form]);

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

  const requiredMissing = useMemo(() => {
    const missing: string[] = [];
    if (!form.traderUserId) missing.push("Trader");
    if (!form.strategyKey) missing.push("Strategy");
    if (!form.symbol) missing.push("Symbol");
    return missing;
  }, [form.traderUserId, form.strategyKey, form.symbol]);

  const preflight = useQuery({
    queryKey: ["admin-test-signal-lab-preflight", form],
    queryFn: () => runTestSignalPreflight(form),
    enabled: requiredMissing.length === 0,
    staleTime: 10_000,
  });

  const runBlockedReason = useMemo(() => {
    if (requiredMissing.length > 0) {
      return `Required: ${requiredMissing.join(", ")}`;
    }
    if (preflight.data && !preflight.data.canSubmit) {
      return preflight.data.blockers?.[0] ?? "Preflight checks failed.";
    }
    return null;
  }, [requiredMissing, preflight.data]);

  const canSubmit = requiredMissing.length === 0 && (preflight.data?.canSubmit ?? true);

  return (
    <div className="space-y-5">
      <div className="rounded-2xl border border-border bg-gradient-to-r from-card via-card to-primary/5 p-4 shadow-sm md:p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Test Signal Lab</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              Isolated infrastructure testing console for signal - execution - broker - terminal verification.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="rounded-full border border-border bg-background px-2.5 py-1 font-medium">
              Traders: {(options.data?.traders ?? []).length}
            </span>
            <span className="rounded-full border border-border bg-background px-2.5 py-1 font-medium">
              Strategies: {(options.data?.strategies ?? []).length}
            </span>
            <span className="rounded-full border border-border bg-background px-2.5 py-1 font-medium">
              Runs: {runs.data?.totalElements ?? 0}
            </span>
          </div>
        </div>
      </div>

      <div className="rounded-2xl border border-border bg-card p-4 shadow-sm md:p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold">Test Configuration</h2>
          <span className="rounded-full border border-amber-300 bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-700">
            Safety-first mode
          </span>
        </div>

        <div className="grid gap-4 md:grid-cols-3">
          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Trader</span>
            <select className={fieldClassName()} value={form.traderUserId} onChange={(e) => setForm((p) => ({ ...p, traderUserId: e.target.value }))}>
              <option value="">Select trader</option>
              {(options.data?.traders ?? []).map((t) => (
                <option key={t.userId} value={t.userId}>{t.displayName || t.username}</option>
              ))}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Broker Account</span>
            <select className={fieldClassName()} value={form.brokerAccountId ?? ""} onChange={(e) => setForm((p) => ({ ...p, brokerAccountId: e.target.value || undefined }))}>
              <option value="">Auto broker</option>
              {brokerOptions.map((b) => (
                <option key={b.id} value={b.id}>{b.vendorCode} ({b.status})</option>
              ))}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Strategy</span>
            <StrategySearchDropdown
              strategies={options.data?.strategies ?? []}
              value={form.strategyKey}
              onChange={(key) => setForm((p) => ({ ...p, strategyKey: key }))}
            />
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Symbol</span>
            <input className={fieldClassName()} placeholder="e.g. RELIANCE" value={form.symbol} onChange={(e) => setForm((p) => ({ ...p, symbol: e.target.value.toUpperCase() }))} />
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Side</span>
            <select className={fieldClassName()} value={form.side} onChange={(e) => setForm((p) => ({ ...p, side: e.target.value as "BUY" | "SELL" }))}>
              <option value="BUY">BUY</option>
              <option value="SELL">SELL</option>
            </select>
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Quantity</span>
            <input type="number" min={1} className={fieldClassName()} value={form.quantity ?? 1} onChange={(e) => setForm((p) => ({ ...p, quantity: Number(e.target.value) }))} />
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Execution Mode</span>
            <select
              className={fieldClassName()}
              value={form.executionMode}
              onChange={(e) =>
                setForm((p) => {
                  const nextMode = e.target.value as "SIMULATED" | "PAPER" | "LIVE" | "BOTH";
                  if (nextMode === "LIVE" || nextMode === "BOTH") {
                    return { ...p, executionMode: nextMode, dryRunOnly: false, skipActualBrokerExecution: false };
                  }
                  if (nextMode === "SIMULATED") {
                    return { ...p, executionMode: nextMode, dryRunOnly: true, skipActualBrokerExecution: true };
                  }
                  return { ...p, executionMode: nextMode };
                })
              }
            >
              {(options.data?.executionModes ?? ["SIMULATED", "PAPER", "LIVE", "BOTH"]).map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Trigger Type</span>
            <select className={fieldClassName()} value={form.triggerType} onChange={(e) => setForm((p) => ({ ...p, triggerType: e.target.value as any }))}>
              {(options.data?.triggerTypes ?? ["INSTANT"]).map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Price (optional)</span>
            <input type="number" className={fieldClassName()} placeholder="Optional limit/trigger price" value={form.price ?? ""} onChange={(e) => setForm((p) => ({ ...p, price: e.target.value ? Number(e.target.value) : undefined }))} />
          </label>

          <label className="space-y-1.5">
            <span className={sectionTitleClassName()}>Auto Square-off (minutes)</span>
            <input type="number" className={fieldClassName()} placeholder="1, 5, etc. Optional" value={form.autoSquareOffMinutes ?? ""} onChange={(e) => setForm((p) => ({ ...p, autoSquareOffMinutes: e.target.value ? Number(e.target.value) : undefined }))} />
          </label>
        </div>

        <div className="mt-5 rounded-xl border border-border bg-muted/20 p-3">
          <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Safety Options</h3>
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
              <label key={k} className="flex items-center gap-2 rounded-md border border-border/60 bg-background px-2.5 py-2 text-xs font-medium">
                <input
                  type="checkbox"
                  checked={Boolean((form as any)[k])}
                  disabled={
                    (k === "dryRunOnly" || k === "skipActualBrokerExecution") &&
                    (form.executionMode === "LIVE" || form.executionMode === "BOTH")
                  }
                  onChange={(e) => setForm((p) => ({ ...p, [k]: e.target.checked } as any))}
                />
                {label}
              </label>
            ))}
          </div>
        </div>

        <div className="mt-5 rounded-xl border border-border bg-background p-3 shadow-sm">
          <div className="mb-2 flex items-center justify-between">
            <h3 className="text-sm font-semibold">Preflight Checks</h3>
            {preflight.isFetching && (
              <span className="text-[11px] text-muted-foreground">Checking...</span>
            )}
            {!preflight.isFetching && requiredMissing.length === 0 && (
              <span className={`inline-flex rounded-full border px-2 py-0.5 text-[10px] font-semibold ${statusPillClassName(preflight.data?.canSubmit ? "SUCCESS" : "FAILED")}`}>
                {preflight.data?.canSubmit ? "READY TO SUBMIT" : "BLOCKED"}
              </span>
            )}
          </div>

          {requiredMissing.length > 0 ? (
            <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">
              Fill required fields first: {requiredMissing.join(", ")}.
            </p>
          ) : preflight.isError ? (
            <p className="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
              Preflight check call failed. Verify admin API connectivity and retry.
            </p>
          ) : (
            <div className="space-y-2">
              {(preflight.data?.checks ?? []).map((c) => (
                <div key={c.key} className="rounded-md border border-border p-2">
                  <div className="mb-1 flex items-center gap-2">
                    <span className="text-xs font-semibold">{c.label}</span>
                    <span className={`inline-flex rounded-full border px-2 py-0.5 text-[10px] font-semibold ${statusPillClassName(c.status)}`}>
                      {c.status}
                    </span>
                  </div>
                  <div className="text-xs text-muted-foreground">{c.message}</div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="mt-5 flex flex-wrap items-center gap-3">
          <button
            type="button"
            className="inline-flex items-center justify-center rounded-lg border border-primary bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground shadow-sm transition hover:opacity-95 disabled:cursor-not-allowed disabled:border-muted-foreground/40 disabled:bg-muted disabled:text-muted-foreground"
            disabled={!canSubmit || runMutation.isPending}
            onClick={() => runMutation.mutate(form)}
          >
            {runMutation.isPending ? "Running..." : "Run Test Signal"}
          </button>
          <button
            type="button"
            className="inline-flex items-center justify-center rounded-lg border border-border bg-muted px-3 py-2.5 text-sm font-semibold text-foreground shadow-sm transition hover:bg-muted/80"
            onClick={() => setForm(DEFAULT_FORM)}
            title="Reset form to defaults"
          >
            Reset Defaults
          </button>
          {runBlockedReason && <span className="text-xs text-amber-600">{runBlockedReason}</span>}
          {runMutation.isError && <span className="text-xs text-red-500">Run failed. Check config.</span>}
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div className="rounded-2xl border border-border bg-card p-4 shadow-sm">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-base font-semibold">Recent Test Runs</h2>
            <span className="text-xs text-muted-foreground">Auto-refresh 15s</span>
          </div>
          <div className="max-h-[520px] overflow-auto">
            <table className="w-full text-left text-xs">
              <thead className="sticky top-0 bg-card text-muted-foreground">
                <tr>
                  <th className="py-2 pr-2">Time</th>
                  <th className="pr-2">Strategy</th>
                  <th className="pr-2">Symbol</th>
                  <th className="pr-2">Status</th>
                </tr>
              </thead>
              <tbody>
                {(runs.data?.content ?? []).map((r) => (
                  <tr
                    key={r.id}
                    className={`cursor-pointer border-t border-border hover:bg-muted/40 ${selectedRunId === r.id ? "bg-primary/5" : ""}`}
                    onClick={() => setSelectedRunId(r.id)}
                  >
                    <td className="py-2 pr-2">{new Date(r.createdAt).toLocaleTimeString()}</td>
                    <td className="pr-2 font-medium">{r.strategyKey}</td>
                    <td className="pr-2">{r.symbol}</td>
                    <td className="pr-2">
                      <span className={`inline-flex rounded-full border px-2 py-0.5 text-[10px] font-semibold ${statusPillClassName(r.finalStatus ?? r.status)}`}>
                        {r.finalStatus ?? r.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="rounded-2xl border border-border bg-card p-4 shadow-sm">
          <h2 className="mb-3 text-base font-semibold">Execution Report</h2>
          {!report.data ? (
            <p className="rounded-lg border border-dashed border-border bg-muted/20 p-3 text-xs text-muted-foreground">
              Select a run from the left panel to view full verification details.
            </p>
          ) : (
            <div className="space-y-3 text-xs">
              <div className="grid gap-2 sm:grid-cols-3">
                <div className="rounded-lg border border-border bg-muted/20 p-2">
                  <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Test ID</div>
                  <div className="mt-1 break-all font-mono text-[11px]">{report.data.testId}</div>
                </div>
                <div className="rounded-lg border border-border bg-muted/20 p-2">
                  <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Final Status</div>
                  <div className="mt-1">
                    <span className={`inline-flex rounded-full border px-2 py-0.5 text-[10px] font-semibold ${statusPillClassName(report.data.finalStatus)}`}>
                      {report.data.finalStatus}
                    </span>
                  </div>
                </div>
                <div className="rounded-lg border border-border bg-muted/20 p-2">
                  <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Total Latency</div>
                  <div className="mt-1 text-sm font-semibold">{report.data.totalLatencyMs ?? 0} ms</div>
                </div>
              </div>

              <div>
                <div className="mb-1 text-sm font-semibold">Checklist</div>
                <div className="space-y-1">
                  {(report.data.checks ?? []).map((c: any) => (
                    <div key={c.key} className="rounded-lg border border-border p-2">
                      <div className="mb-1 flex items-center gap-2 font-medium">
                        <span className="truncate">{c.label}</span>
                        <span className={`inline-flex rounded-full border px-2 py-0.5 text-[10px] font-semibold ${statusPillClassName(c.status)}`}>
                          {c.status}
                        </span>
                      </div>
                      <div className="text-muted-foreground">{c.message}</div>
                      {c.suggestedAction && <div className="mt-1 text-amber-600">Action: {c.suggestedAction}</div>}
                      {c.actionCode && (
                        <button
                          type="button"
                          className="mt-2 rounded-md border border-border px-2.5 py-1 text-[11px] font-semibold hover:bg-muted/40"
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
                <div className="mb-1 text-sm font-semibold">Pipeline Timeline</div>
                <div className="max-h-48 space-y-1 overflow-auto">
                  {(report.data.timeline ?? []).map((t: any, idx: number) => (
                    <div key={idx} className="rounded-lg border border-border p-2">
                      <div className="font-medium">{t.stage}</div>
                      <div className="text-muted-foreground">{t.at ? new Date(t.at).toLocaleString() : "-"} {t.detail ? `- ${t.detail}` : ""}</div>
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
