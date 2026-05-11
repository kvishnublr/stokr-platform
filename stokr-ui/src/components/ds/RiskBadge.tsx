import { cn } from "../../lib/utils";

type RiskBadgeVariant = "dark" | "light";

export function RiskBadge({ level, variant = "dark" }: { level: string; variant?: RiskBadgeVariant }) {
  const u = level.toUpperCase();
  const style =
    variant === "light"
      ? u === "LOW"
        ? "border-emerald-200 bg-emerald-50 text-emerald-800"
        : u === "MEDIUM" || u === "MID"
          ? "border-amber-200 bg-amber-50 text-amber-800"
          : u === "HIGH"
            ? "border-orange-200 bg-orange-50 text-orange-800"
            : "border-rose-200 bg-rose-50 text-rose-800"
      : u === "LOW"
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
