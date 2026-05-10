import { cn } from "../../lib/utils";

/**
 * Displays OMS lifecycle state with institutional semantics.
 */
export function ExecutionBadge({ state }: { state: string }) {
  const s = state.toUpperCase();
  const ok = ["FILLED", "PARTIALLY_FILLED", "ACCEPTED", "QUEUED", "ACKNOWLEDGED", "SENT"].includes(s);
  const warn = ["CREATED", "VALIDATING", "RISK_CHECK"].includes(s);
  const bad = ["REJECTED", "CANCELLED"].includes(s);
  return (
    <span
      className={cn(
        "rounded-md px-2 py-0.5 font-mono text-[10px] uppercase tracking-wide",
        ok && "bg-emerald-500/15 text-emerald-200 ring-1 ring-emerald-500/25",
        warn && !ok && !bad && "bg-amber-500/10 text-amber-100 ring-1 ring-amber-500/20",
        bad && "bg-rose-500/15 text-rose-100 ring-1 ring-rose-500/25",
        !ok && !warn && !bad && "bg-neutral-800 text-neutral-300 ring-1 ring-neutral-700",
      )}
    >
      {state}
    </span>
  );
}
