import { buildDependencyChain, computeSystemReadiness } from "./adminReadinessModel";
import type { OpsSnapshot } from "./cockpit/opsTypes";

export function SystemReadinessBanner({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const r = computeSystemReadiness(snapshot);
  const chain = buildDependencyChain(snapshot);

  const border =
    r.level === "OFFLINE"
      ? "border-red-600/50 bg-red-600/10"
      : r.level === "BACKFILLING"
        ? "border-orange-500/50 bg-orange-500/10"
        : r.level === "LIMITED"
          ? "border-amber-500/50 bg-amber-500/10"
          : r.level === "DEGRADED"
            ? "border-amber-500/45 bg-amber-500/8"
            : "border-emerald-600/40 bg-emerald-600/8";

  const chip =
    r.level === "READY"
      ? "border-emerald-600/50 bg-emerald-600/15 text-emerald-800 dark:text-emerald-100"
      : r.level === "OFFLINE"
        ? "border-red-600/50 bg-red-600/15 text-red-900 dark:text-red-100"
        : r.level === "BACKFILLING"
          ? "border-orange-500/50 bg-orange-500/15 text-orange-950 dark:text-orange-100"
          : "border-amber-500/50 bg-amber-500/15 text-amber-950 dark:text-amber-100";

  function stepChip(st: string): string {
    if (st === "OK") return "border-emerald-600/40 bg-emerald-600/10 text-emerald-900 dark:text-emerald-100";
    if (st === "PAUSED" || st === "OFFLINE" || st === "UNAVAILABLE")
      return "border-red-500/40 bg-red-500/10 text-red-900 dark:text-red-100";
    if (st === "BACKFILLING") return "border-orange-500/40 bg-orange-500/10 text-orange-950 dark:text-orange-100";
    return "border-amber-500/40 bg-amber-500/10 text-amber-950 dark:text-amber-100";
  }

  return (
    <div className={`rounded-xl border px-4 py-4 ${border}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`rounded-md border px-2 py-0.5 font-mono text-[10px] font-bold uppercase tracking-wide ${chip}`}>
              {r.level}
            </span>
            {r.killSwitch ? (
              <span className="rounded-md border border-red-600/50 bg-red-600/20 px-2 py-0.5 font-mono text-[10px] font-bold text-red-900 dark:text-red-100">
                Kill switch ON
              </span>
            ) : null}
          </div>
          <h2 className="mt-2 text-lg font-bold tracking-tight text-foreground">{r.headline}</h2>
          <p className="mt-1 max-w-4xl text-sm leading-relaxed text-muted-foreground">{r.subline}</p>
        </div>
      </div>

      <div className="mt-4 border-t border-border/80 pt-3">
        <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">Dependency chain</div>
        <ol className="mt-2 flex flex-wrap gap-2">
          {chain.map((step, i) => (
            <li key={step.id} className="flex min-w-0 items-center gap-2">
              {i > 0 ? <span className="text-muted-foreground">→</span> : null}
              <div
                className={`min-w-[8.5rem] max-w-[14rem] rounded-lg border px-2 py-1.5 ${stepChip(step.state)}`}
                title={step.detail}
              >
                <div className="truncate text-[10px] font-bold uppercase tracking-wide text-foreground/90">{step.label}</div>
                <div className="truncate font-mono text-[10px] font-semibold">{step.state}</div>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}
