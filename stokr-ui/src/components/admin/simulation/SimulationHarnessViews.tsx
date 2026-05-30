import { useState } from "react";
import {
  ArrowRight,
  BarChart3,
  CheckCircle2,
  ChevronDown,
  Circle,
  Copy,
  Database,
  GitBranch,
  LineChart,
  Shield,
  Target,
  TrendingUp,
  XCircle,
  Zap,
  type LucideIcon,
} from "lucide-react";
import type {
  HarnessValidation,
  ScenarioValidationResult,
  SimulationAggregates,
  SimulationHarnessReport,
  SimulationRunRow,
  SimulationSignalRow,
  ValidationPackReport,
} from "../../../api/simulation";
import { AdminPanel, AdminStatusChip } from "../institutional/AdminDesignSystem";
import { cn } from "../../../lib/utils";

export type PipelineStepStatus = "pass" | "fail" | "skip";

export type PipelineStepView = {
  id: string;
  label: string;
  shortLabel: string;
  icon: LucideIcon;
  status: PipelineStepStatus;
  detail?: string;
  optional?: boolean;
};

function asSteps(validation: HarnessValidation): string[] {
  return validation.pipelineSteps ?? [];
}

export function derivePipelineSteps(validation: HarnessValidation): PipelineStepView[] {
  const steps = asSteps(validation);
  const has = (token: string) => steps.some((s) => s.includes(token));

  const afterSeed = has("SEEDED");
  const afterSignal = validation.signalGenerated === true;
  const afterOms = has("OMS_ORDER") || has("OMS_NO_ORDER");

  const defs: Array<Omit<PipelineStepView, "status" | "detail">> = [
    { id: "seed", label: "Market Data Seeded", shortLabel: "Seed", icon: Database },
    { id: "signal", label: "Strategy Signal", shortLabel: "Signal", icon: GitBranch },
    { id: "confidence", label: "Confidence V2", shortLabel: "Conf V2", icon: BarChart3 },
    { id: "oms", label: "OMS Order", shortLabel: "OMS", icon: Zap },
    { id: "execution", label: "Execution", shortLabel: "Exec", icon: TrendingUp },
    { id: "outcome", label: "Outcome", shortLabel: "Outcome", icon: Target },
    { id: "protection", label: "Protection", shortLabel: "Protect", icon: Shield, optional: true },
  ];

  return defs.map((def) => {
    let status: PipelineStepStatus = "skip";
    let detail: string | undefined;

    switch (def.id) {
      case "seed":
        if (has("SEEDED")) status = "pass";
        else if (validation.error && steps.length <= 1) status = "fail";
        else status = "skip";
        break;
      case "signal":
        if (afterSignal) status = "pass";
        else if (afterSeed) status = "fail";
        else status = "skip";
        if (status === "fail" && validation.error) detail = "No signal";
        break;
      case "confidence":
        if (validation.confidenceV2) status = "pass";
        else if (afterSignal) status = "fail";
        else status = "skip";
        break;
      case "oms":
        if (validation.omsExecuted) {
          status = "pass";
          detail = validation.orderState ?? undefined;
        } else if (has("OMS_NO_ORDER") || afterSignal) status = "fail";
        else status = "skip";
        break;
      case "execution":
        if (has("LIVE_TICKS")) status = "pass";
        else if (afterOms && !has("LIVE_TICKS")) status = "skip";
        else status = "skip";
        break;
      case "outcome":
        if (has("OUTCOME") && validation.outcomeStatus) {
          status = "pass";
          detail = validation.outcomeStatus;
        } else if (has("OUTCOME")) status = "fail";
        else status = "skip";
        break;
      case "protection":
        if (!has("PROTECTION")) return { ...def, status: "skip" as const };
        status = "pass";
        if (validation.protectionTriggered) detail = "Triggered";
        break;
    }

    return { ...def, status, detail };
  });
}

function StepIcon({ status, isLight, small }: { status: PipelineStepStatus; isLight: boolean; small?: boolean }) {
  const cls = small ? "h-3.5 w-3.5" : "h-5 w-5";
  if (status === "pass") return <CheckCircle2 className={cn(cls, "text-emerald-500")} />;
  if (status === "fail") return <XCircle className={cn(cls, "text-rose-500")} />;
  return <Circle className={cn(cls, isLight ? "text-neutral-300" : "text-neutral-600")} />;
}

export function SimulationPipelineStepper({
  validation,
  isLight,
  compact,
}: {
  validation: HarnessValidation;
  isLight: boolean;
  compact?: boolean;
}) {
  const steps = derivePipelineSteps(validation).filter((s) => !(s.optional && s.status === "skip"));

  return (
    <div className={cn("w-full", compact ? "py-1" : "py-2")}>
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:gap-0">
        {steps.map((step, idx) => {
          const Icon = step.icon;
          const isLast = idx === steps.length - 1;
          const ring =
            step.status === "pass"
              ? isLight
                ? "border-emerald-200 bg-emerald-50"
                : "border-emerald-500/30 bg-emerald-500/10"
              : step.status === "fail"
                ? isLight
                  ? "border-rose-200 bg-rose-50"
                  : "border-rose-500/30 bg-rose-500/10"
                : isLight
                  ? "border-neutral-200 bg-neutral-50"
                  : "border-neutral-700 bg-neutral-900/40";

          return (
            <div key={step.id} className="flex min-w-0 flex-1 flex-col items-center lg:flex-row lg:items-start">
              <div className="flex w-full flex-col items-center text-center lg:w-auto">
                <div
                  className={cn(
                    "relative flex h-14 w-14 items-center justify-center rounded-xl border-2 transition-colors",
                    ring,
                  )}
                >
                  <Icon
                    className={cn(
                      "h-5 w-5",
                      step.status === "pass"
                        ? "text-emerald-600"
                        : step.status === "fail"
                          ? "text-rose-600"
                          : isLight
                            ? "text-neutral-400"
                            : "text-neutral-500",
                    )}
                  />
                  <span className="absolute -bottom-1 -right-1 rounded-full bg-inherit p-0.5">
                    <StepIcon status={step.status} isLight={isLight} small />
                  </span>
                </div>
                <p
                  className={cn(
                    "mt-2 max-w-[88px] text-[10px] font-semibold uppercase leading-tight tracking-wide",
                    isLight ? "text-neutral-700" : "text-neutral-300",
                  )}
                >
                  {step.shortLabel}
                </p>
                {!compact && (
                  <p className={cn("mt-0.5 max-w-[100px] text-[9px] leading-tight", isLight ? "text-neutral-500" : "text-neutral-500")}>
                    {step.label}
                  </p>
                )}
                {step.detail && (
                  <p
                    className={cn(
                      "mt-1 max-w-[100px] truncate font-mono text-[9px]",
                      step.status === "pass" ? "text-emerald-600" : step.status === "fail" ? "text-rose-500" : "text-neutral-500",
                    )}
                    title={step.detail}
                  >
                    {step.detail}
                  </p>
                )}
              </div>
              {!isLast && (
                <>
                  <ArrowRight
                    className={cn(
                      "mx-2 mt-5 hidden h-4 w-4 shrink-0 lg:block",
                      step.status === "pass" ? "text-emerald-500/70" : isLight ? "text-neutral-300" : "text-neutral-600",
                    )}
                  />
                  <div className={cn("my-2 h-6 w-px lg:hidden", isLight ? "bg-neutral-200" : "bg-neutral-700")} />
                </>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function CopyChip({ label, value, isLight }: { label: string; value: string; isLight: boolean }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* ignore */
    }
  }

  return (
    <button
      type="button"
      onClick={copy}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 font-mono text-[11px] transition hover:opacity-90",
        isLight ? "border-neutral-200 bg-neutral-50 text-neutral-800" : "border-neutral-700 bg-neutral-900/60 text-neutral-200",
      )}
      title={`Copy ${label}`}
    >
      <span className={cn("text-[9px] font-sans uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>
        {label}
      </span>
      <span>{value.slice(0, 8)}…</span>
      <Copy className="h-3 w-3 opacity-60" />
      {copied && <span className="text-[9px] text-emerald-500">Copied</span>}
    </button>
  );
}

function TechnicalJsonAccordion({ data, isLight }: { data: unknown; isLight: boolean }) {
  const [open, setOpen] = useState(false);

  return (
    <div className={cn("mt-4 rounded-xl border", isLight ? "border-neutral-200" : "border-neutral-800")}>
      <button
        type="button"
        className={cn(
          "flex w-full items-center justify-between px-4 py-2.5 text-left text-xs font-semibold uppercase tracking-wide",
          isLight ? "text-neutral-600 hover:bg-neutral-50" : "text-neutral-400 hover:bg-neutral-900/50",
        )}
        onClick={() => setOpen((o) => !o)}
      >
        Technical JSON
        <ChevronDown className={cn("h-4 w-4 transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <pre
          className={cn(
            "max-h-64 overflow-auto border-t px-4 py-3 font-mono text-[11px] leading-relaxed",
            isLight ? "border-neutral-200 bg-neutral-50 text-neutral-800" : "border-neutral-800 bg-black/30 text-neutral-300",
          )}
        >
          {JSON.stringify(data, null, 2)}
        </pre>
      )}
    </div>
  );
}

function formatPnl(value: number | string | null | undefined): string {
  if (value == null || value === "") return "—";
  const n = typeof value === "number" ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  const sign = n >= 0 ? "+" : "";
  return `${sign}${n.toFixed(2)}`;
}

function formatConfidence(value: number | null | undefined): string {
  if (value == null) return "—";
  return value.toFixed(3);
}

export function SimulationRunResultCard({
  report,
  isLight,
  confidenceScore,
  confidenceVersion,
}: {
  report: SimulationHarnessReport;
  isLight: boolean;
  confidenceScore?: number | null;
  confidenceVersion?: string | null;
}) {
  const v = report.validation;
  const confidenceLabel =
    confidenceScore != null
      ? formatConfidence(confidenceScore)
      : v.confidenceV2
        ? "V2 OK"
        : v.confidencePersisted
          ? "persisted"
          : "—";
  const versionLabel = confidenceVersion ?? (v.confidenceV2 ? "CONFIDENCE_V2" : "—");

  return (
    <AdminPanel
      isLight={isLight}
      className={cn(
        "mt-4 border-2",
        report.success
          ? isLight
            ? "border-emerald-200/80"
            : "border-emerald-500/25"
          : isLight
            ? "border-rose-200/80"
            : "border-rose-500/25",
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h3 className={cn("text-base font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>
              {report.scenario}
            </h3>
            <AdminStatusChip tone={report.success ? "success" : "critical"} isLight={isLight}>
              {report.success ? "PASSED" : "FAILED"}
            </AdminStatusChip>
          </div>
          <p className={cn("mt-1 text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>
            {report.symbol} · {report.strategyKey}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {report.simulationRunId && <CopyChip label="run" value={report.simulationRunId} isLight={isLight} />}
          {report.signalId && <CopyChip label="signal" value={report.signalId} isLight={isLight} />}
        </div>
      </div>

      <div className="mt-4">
        <SimulationPipelineStepper validation={v} isLight={isLight} />
      </div>

      {v.error && (
        <div
          className={cn(
            "mt-4 rounded-lg border px-3 py-2 text-sm",
            isLight ? "border-rose-200 bg-rose-50 text-rose-800" : "border-rose-500/30 bg-rose-500/10 text-rose-200",
          )}
        >
          {v.error}
        </div>
      )}

      <div className="mt-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {[
          { label: "Confidence", value: confidenceLabel, sub: versionLabel },
          { label: "Outcome", value: v.outcomeStatus ?? "—" },
          { label: "Order state", value: v.orderState ?? "—" },
          { label: "Realized PnL", value: formatPnl(v.realizedPnl) },
        ].map((m) => (
          <div
            key={m.label}
            className={cn("rounded-lg border px-3 py-2", isLight ? "border-neutral-200 bg-white/60" : "border-neutral-800 bg-neutral-950/30")}
          >
            <p className="text-[10px] font-medium uppercase tracking-wide text-neutral-500">{m.label}</p>
            <p className={cn("mt-0.5 font-mono text-sm font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>
              {m.value}
            </p>
            {"sub" in m && m.sub && m.sub !== "—" && (
              <p className={cn("mt-0.5 font-mono text-[10px]", isLight ? "text-neutral-500" : "text-neutral-500")}>{m.sub}</p>
            )}
          </div>
        ))}
      </div>

      <TechnicalJsonAccordion data={report} isLight={isLight} />
    </AdminPanel>
  );
}

function StatCard({
  label,
  value,
  isLight,
  accent,
}: {
  label: string;
  value: string | number;
  isLight: boolean;
  accent?: "emerald" | "amber" | "rose" | "blue";
}) {
  const accentRing =
    accent === "emerald"
      ? isLight
        ? "border-emerald-200 bg-emerald-50/80"
        : "border-emerald-500/25 bg-emerald-500/10"
      : accent === "rose"
        ? isLight
          ? "border-rose-200 bg-rose-50/80"
          : "border-rose-500/25 bg-rose-500/10"
        : accent === "amber"
          ? isLight
            ? "border-amber-200 bg-amber-50/80"
            : "border-amber-500/25 bg-amber-500/10"
          : isLight
            ? "border-neutral-200 bg-neutral-50/80"
            : "border-neutral-800 bg-neutral-950/40";

  return (
    <div className={cn("rounded-xl border px-3 py-3", accentRing)}>
      <p className="text-[10px] font-medium uppercase tracking-wide text-neutral-500">{label}</p>
      <p className={cn("mt-1 font-mono text-xl font-semibold tabular-nums", isLight ? "text-neutral-900" : "text-neutral-100")}>
        {value}
      </p>
    </div>
  );
}

export function SimulationDashboardPanel({
  runs,
  signals,
  aggregates,
  orderCount,
  isLight,
  isLoading,
  focusedRunId,
}: {
  runs: SimulationRunRow[];
  signals: SimulationSignalRow[];
  aggregates: SimulationAggregates;
  orderCount?: number;
  isLight: boolean;
  isLoading?: boolean;
  focusedRunId?: string;
}) {
  if (isLoading) {
    return (
      <AdminPanel isLight={isLight}>
        <p className="text-sm text-neutral-500">Loading dashboard…</p>
      </AdminPanel>
    );
  }

  const filledOrders = signals.filter((s) => s.orderState === "FILLED" || s.orderState === "COMPLETED").length;

  return (
    <div className="space-y-4">
      {focusedRunId && (
        <p className={cn("text-xs", isLight ? "text-blue-700" : "text-blue-400")}>
          Filtered to run <span className="font-mono">{focusedRunId.slice(0, 8)}…</span>
        </p>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        <StatCard label="Signals" value={aggregates.signals ?? signals.length} isLight={isLight} accent="blue" />
        <StatCard label="Target hits" value={aggregates.targetHits ?? 0} isLight={isLight} accent="emerald" />
        <StatCard label="SL hits" value={aggregates.stopLosses ?? 0} isLight={isLight} accent="rose" />
        <StatCard label="Protection exits" value={aggregates.protectionExits ?? 0} isLight={isLight} accent="amber" />
        <StatCard label="Orders" value={orderCount ?? 0} isLight={isLight} />
        <StatCard label="Executions" value={filledOrders} isLight={isLight} accent="emerald" />
      </div>

      <AdminPanel isLight={isLight} title="Runs" noPadding>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className={cn("border-b", isLight ? "border-neutral-200 text-neutral-500" : "border-neutral-800 text-neutral-400")}>
                <th className="px-4 py-2.5 font-semibold">Scenario</th>
                <th className="px-4 py-2.5 font-semibold">Status</th>
                <th className="px-4 py-2.5 font-semibold">Success</th>
                <th className="px-4 py-2.5 font-semibold">Started</th>
                <th className="px-4 py-2.5 font-semibold">Signals</th>
              </tr>
            </thead>
            <tbody>
              {runs.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-neutral-500">
                    No simulation runs yet
                  </td>
                </tr>
              )}
              {runs.map((r) => (
                <tr
                  key={r.runId}
                  className={cn(
                    "border-b transition-colors",
                    isLight ? "border-neutral-100 hover:bg-neutral-50" : "border-neutral-800/60 hover:bg-neutral-900/40",
                    focusedRunId === r.runId && (isLight ? "bg-blue-50/50" : "bg-blue-500/5"),
                  )}
                >
                  <td className="px-4 py-2.5 font-medium">{r.scenario}</td>
                  <td className="px-4 py-2.5">{r.status}</td>
                  <td className="px-4 py-2.5">
                    <AdminStatusChip tone={r.success ? "success" : "critical"} isLight={isLight}>
                      {r.success ? "Yes" : "No"}
                    </AdminStatusChip>
                  </td>
                  <td className="px-4 py-2.5 font-mono text-[11px]">{formatWhen(r.startedAt)}</td>
                  <td className="px-4 py-2.5 font-mono">{r.signalCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </AdminPanel>

      {(focusedRunId || signals.length > 0) && (
        <AdminPanel isLight={isLight} title="Signals" subtitle={focusedRunId ? "Signals for selected run" : undefined} noPadding>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className={cn("border-b", isLight ? "border-neutral-200 text-neutral-500" : "border-neutral-800 text-neutral-400")}>
                  <th className="px-4 py-2.5 font-semibold">Strategy</th>
                  <th className="px-4 py-2.5 font-semibold">Symbol</th>
                  <th className="px-4 py-2.5 font-semibold">Confidence</th>
                  <th className="px-4 py-2.5 font-semibold">Outcome</th>
                  <th className="px-4 py-2.5 font-semibold">Order</th>
                </tr>
              </thead>
              <tbody>
                {signals.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-4 py-6 text-center text-neutral-500">
                      No signals for this run
                    </td>
                  </tr>
                )}
                {signals.map((s) => (
                  <tr
                    key={s.signalId}
                    className={cn("border-b", isLight ? "border-neutral-100" : "border-neutral-800/60")}
                  >
                    <td className="px-4 py-2.5">{s.strategy}</td>
                    <td className="px-4 py-2.5 font-medium">{s.symbol}</td>
                    <td className="px-4 py-2.5 font-mono">
                      {s.confidence != null ? s.confidence.toFixed(3) : "—"}
                      {s.confidenceVersion && (
                        <span className={cn("ml-1 text-[10px]", isLight ? "text-neutral-500" : "text-neutral-500")}>
                          {s.confidenceVersion}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-2.5">{s.outcomeStatus ?? "—"}</td>
                    <td className="px-4 py-2.5">{s.orderState ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </AdminPanel>
      )}
    </div>
  );
}

function formatWhen(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return iso;
  }
}

export function ValidationPackResults({
  report,
  isLight,
}: {
  report: ValidationPackReport;
  isLight: boolean;
}) {
  const passed = report.scenarios.filter((s) => s.passed).length;

  return (
    <div className="mt-4 space-y-4">
      <div
        className={cn(
          "flex flex-wrap items-center justify-between gap-3 rounded-xl border px-4 py-3",
          report.allPassed
            ? isLight
              ? "border-emerald-200 bg-emerald-50"
              : "border-emerald-500/30 bg-emerald-500/10"
            : isLight
              ? "border-rose-200 bg-rose-50"
              : "border-rose-500/30 bg-rose-500/10",
        )}
      >
        <div className="flex items-center gap-2">
          {report.allPassed ? (
            <CheckCircle2 className="h-5 w-5 text-emerald-600" />
          ) : (
            <XCircle className="h-5 w-5 text-rose-600" />
          )}
          <div>
            <p className={cn("text-sm font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>
              Release validation pack — {report.allPassed ? "ALL PASSED" : "FAILURES DETECTED"}
            </p>
            <p className={cn("text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
              {passed} / {report.scenarios.length} scenarios passed
            </p>
          </div>
        </div>
        <LineChart className={cn("h-8 w-8 opacity-40", report.allPassed ? "text-emerald-600" : "text-rose-600")} />
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {report.scenarios.map((s) => (
          <ScenarioTile key={s.scenario} result={s} isLight={isLight} />
        ))}
      </div>

      {report.analyticsIsolation && Object.keys(report.analyticsIsolation).length > 0 && (
        <AdminPanel isLight={isLight} title="Analytics isolation">
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            {Object.entries(report.analyticsIsolation).map(([k, v]) => (
              <div
                key={k}
                className={cn("rounded-lg border px-3 py-2", isLight ? "border-neutral-200" : "border-neutral-800")}
              >
                <p className="text-[10px] uppercase text-neutral-500">{k}</p>
                <p className="font-mono text-sm font-semibold">{String(v)}</p>
              </div>
            ))}
          </div>
        </AdminPanel>
      )}

      <TechnicalJsonAccordion data={report} isLight={isLight} />
    </div>
  );
}

function ScenarioTile({ result, isLight }: { result: ScenarioValidationResult; isLight: boolean }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border-2 transition-colors",
        result.passed
          ? isLight
            ? "border-emerald-200 bg-emerald-50/50"
            : "border-emerald-500/25 bg-emerald-500/5"
          : isLight
            ? "border-rose-200 bg-rose-50/50"
            : "border-rose-500/25 bg-rose-500/5",
      )}
    >
      <button
        type="button"
        className="flex w-full items-center justify-between gap-2 px-3 py-3 text-left"
        onClick={() => setExpanded((e) => !e)}
      >
        <div className="min-w-0">
          <p className={cn("truncate text-sm font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>
            {result.scenario}
          </p>
          <AdminStatusChip tone={result.passed ? "success" : "critical"} isLight={isLight} className="mt-1">
            {result.passed ? "PASS" : "FAIL"}
          </AdminStatusChip>
        </div>
        {result.passed ? (
          <CheckCircle2 className="h-6 w-6 shrink-0 text-emerald-500" />
        ) : (
          <XCircle className="h-6 w-6 shrink-0 text-rose-500" />
        )}
      </button>
      {expanded && (
        <div className={cn("border-t px-3 pb-3", isLight ? "border-emerald-100" : "border-neutral-800")}>
          <SimulationPipelineStepper validation={result.validation} isLight={isLight} compact />
          {result.validation.error && (
            <p className="mt-2 text-[11px] text-rose-600">{result.validation.error}</p>
          )}
        </div>
      )}
    </div>
  );
}
