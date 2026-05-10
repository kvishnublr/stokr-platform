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
      "border-emerald-500/35 bg-emerald-500/10 text-emerald-200 ring-emerald-500/20 animate-pulse [animation-duration:2.5s]",
  },
  degraded: {
    label: "Degraded",
    className: "border-amber-500/40 bg-amber-500/10 text-amber-200",
  },
  offline: {
    label: "Offline",
    className: "border-rose-500/35 bg-rose-500/10 text-rose-200",
  },
  pending: {
    label: "Pending",
    className: "border-neutral-600 bg-neutral-800/70 text-neutral-300",
  },
  paper: {
    label: "Paper",
    className: "border-sky-500/35 bg-sky-500/10 text-sky-200",
  },
  live: {
    label: "Live",
    className:
      "border-violet-500/40 bg-violet-500/10 text-violet-100 animate-pulse [animation-duration:2.8s]",
  },
  neutral: {
    label: "Idle",
    className: "border-neutral-700 bg-neutral-900 text-neutral-400",
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
