import { cn } from "../../lib/utils";

/**
 * Displays OMS lifecycle state with institutional semantics.
 */
export function ExecutionBadge({ state }: { state: string }) {
  const s = state.toUpperCase();
  const ok = ["FILLED", "PARTIALLY_FILLED", "ACCEPTED", "PENDING_SUBMISSION", "SUBMITTED", "EXIT_FILLED"].includes(s);
  const warn = ["CREATED", "VALIDATED", "RISK_CHECK"].includes(s);
  const bad = ["REJECTED", "CANCELLED"].includes(s);
  return (
    <span
      className={cn(
        "rounded-md px-2 py-0.5 font-mono text-[10px] uppercase tracking-wide ring-1",
        ok && "border-emerald-300 bg-emerald-50 text-emerald-800 ring-emerald-300/50 dark:border-emerald-500/25 dark:bg-emerald-500/15 dark:text-emerald-200 dark:ring-emerald-500/25",
        warn && !ok && !bad && "border-amber-300 bg-amber-50 text-amber-950 ring-amber-300/50 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-100 dark:ring-amber-500/20",
        bad && "border-rose-300 bg-rose-50 text-rose-900 ring-rose-300/50 dark:border-rose-500/25 dark:bg-rose-500/15 dark:text-rose-100 dark:ring-rose-500/25",
        !ok && !warn && !bad && "border-neutral-300 bg-neutral-100 text-neutral-700 ring-neutral-300/50 dark:border-neutral-700 dark:bg-neutral-800 dark:text-neutral-300 dark:ring-neutral-700",
      )}
    >
      {state}
    </span>
  );
}
