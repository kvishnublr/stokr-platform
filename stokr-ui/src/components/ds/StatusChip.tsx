import { cn } from "../../lib/utils";

export type OperationalStatus =
  | "online"
  | "degraded"
  | "offline"
  | "pending"
  | "paper"
  | "live"
  | "neutral";

const MAP: Record<OperationalStatus, { label: string; className: string }> = {
  online: {
    label: "Online",
    className:
      "border-emerald-300 bg-emerald-50 text-emerald-800 dark:border-emerald-500/35 dark:bg-emerald-500/10 dark:text-emerald-200 ring-emerald-500/20 animate-pulse [animation-duration:2.5s]",
  },
  degraded: {
    label: "Degraded",
    className:
      "border-amber-300 bg-amber-50 text-amber-950 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-100",
  },
  offline: {
    label: "Offline",
    className:
      "border-rose-300 bg-rose-50 text-rose-900 dark:border-rose-500/35 dark:bg-rose-500/10 dark:text-rose-200",
  },
  pending: {
    label: "Pending",
    className:
      "border-neutral-300 bg-neutral-100 text-neutral-700 dark:border-neutral-600 dark:bg-neutral-800/70 dark:text-neutral-300",
  },
  paper: {
    label: "Paper",
    className:
      "border-sky-300 bg-sky-50 text-sky-900 dark:border-sky-500/35 dark:bg-sky-500/10 dark:text-sky-200",
  },
  live: {
    label: "Live",
    className:
      "border-violet-300 bg-violet-50 text-violet-900 dark:border-violet-500/40 dark:bg-violet-500/10 dark:text-violet-100 animate-pulse [animation-duration:2.8s]",
  },
  neutral: {
    label: "Idle",
    className:
      "border-neutral-300 bg-neutral-100 text-neutral-600 dark:border-neutral-700 dark:bg-neutral-900 dark:text-neutral-400",
  },
};

export function StatusChip({ status, label }: { status: OperationalStatus; label?: string }) {
  const c = MAP[status];
  return (
    <span
      role="status"
      className={cn(
        "inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider ring-1 ring-inset",
        c.className,
      )}
    >
      {label ?? c.label}
    </span>
  );
}
