import { cn } from "../../lib/utils";

export function RiskBadge({ level }: { level: string }) {
  const u = level.toUpperCase();
  const style =
    u === "LOW"
      ? "border-emerald-500/35 bg-emerald-500/10 text-emerald-200"
      : u === "MEDIUM" || u === "MID"
        ? "border-amber-500/40 bg-amber-500/12 text-amber-100"
        : u === "HIGH"
          ? "border-orange-500/40 bg-orange-500/12 text-orange-100"
          : "border-rose-500/40 bg-rose-500/15 text-rose-100";
  return (
    <span
      className={cn(
        "rounded-lg border px-2 py-0.5 text-[10px] font-bold uppercase tracking-widest backdrop-blur",
        style,
      )}
    >
      Risk {level}
    </span>
  );
}
