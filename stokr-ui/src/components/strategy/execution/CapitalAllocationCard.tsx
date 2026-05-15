import { motion } from "framer-motion";
import { cn } from "../../../lib/utils";
import { useUiThemeStore } from "../../../state/uiTheme";

const CHIPS = [10_000, 25_000, 50_000, 100_000, 500_000] as const;

type Props = {
  value: number;
  onChange: (n: number) => void;
  disabled?: boolean;
  className?: string;
};

function formatInr(n: number): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(n);
}

function chipLabel(amt: number): string {
  if (amt === 100_000) return "₹1L";
  if (amt === 500_000) return "₹5L";
  if (amt >= 1_000) return `₹${amt / 1_000}K`;
  return formatInr(amt);
}

export function CapitalAllocationCard({ value, onChange, disabled, className }: Props) {
  const isLight = useUiThemeStore((s) => s.mode === "light");

  return (
    <section
      className={cn(
        "rounded-2xl border p-5 sm:p-6 shadow-sm",
        isLight ? "border-slate-900/[0.08] bg-white" : "border-[rgba(255,255,255,0.06)] bg-[#111827]",
        className,
      )}
    >
      <p className="text-[10px] font-semibold uppercase tracking-[0.2em] text-[#64748B]">Capital allocation</p>
      <p className={cn("mt-1 text-sm", isLight ? "text-[#475569]" : "text-[#94A3B8]")}>Notional capital for this replay run.</p>
      <div className="mt-4 flex flex-wrap gap-2">
        {CHIPS.map((amt) => {
          const active = value === amt;
          return (
            <motion.button
              key={amt}
              type="button"
              disabled={disabled}
              whileTap={{ scale: 0.97 }}
              onClick={() => onChange(amt)}
              className={cn(
                "rounded-full border px-3.5 py-1.5 text-xs font-semibold transition-colors",
                active
                  ? "border-[#2563EB] bg-[#2563EB] text-white shadow-sm"
                  : isLight
                    ? "border-slate-900/[0.1] bg-[#F8FAFC] text-[#475569] hover:border-blue-500/35 hover:text-[#0F172A]"
                    : "border-[rgba(255,255,255,0.08)] bg-[#0B1220] text-[#CBD5E1] hover:border-[rgba(37,99,235,0.45)] hover:text-white",
                disabled && "opacity-40",
              )}
            >
              {chipLabel(amt)}
            </motion.button>
          );
        })}
      </div>
      <label className="mt-4 block text-[11px] font-medium text-[#64748B]">
        Custom amount (INR)
        <input
          type="number"
          min={1}
          step={1000}
          disabled={disabled}
          value={Number.isFinite(value) ? value : ""}
          onChange={(e) => onChange(Number(e.target.value))}
          className={cn(
            "mt-1.5 w-full rounded-xl border px-3 py-2.5 text-sm font-medium tabular-nums outline-none transition",
            isLight
              ? "border-slate-900/[0.1] bg-white text-[#0F172A] focus:border-[#2563EB]/80 [color-scheme:light]"
              : "border-[rgba(255,255,255,0.08)] bg-[#0B1220] text-[#F8FAFC] focus:border-[#2563EB]/80",
            disabled && "opacity-40",
          )}
        />
      </label>
    </section>
  );
}
