import { motion } from "framer-motion";
import { cn } from "../../../lib/utils";
import { useUiThemeStore } from "../../../state/uiTheme";

export type ExecutionModeChoice = "BACKTEST" | "PAPER" | "LIVE";

type Props = {
  value: ExecutionModeChoice;
  onChange: (m: ExecutionModeChoice) => void;
  disabled?: boolean;
  className?: string;
};

const MODES: { id: ExecutionModeChoice; label: string; hint: string }[] = [
  { id: "BACKTEST", label: "Backtest", hint: "Historical replay" },
  { id: "PAPER", label: "Paper", hint: "Simulated live" },
  { id: "LIVE", label: "Live", hint: "Broker execution" },
];

export function ExecutionModeToggle({ value, onChange, disabled, className }: Props) {
  const isLight = useUiThemeStore((s) => s.mode === "light");

  return (
    <section
      className={cn(
        "rounded-2xl border p-5 sm:p-6 shadow-sm",
        isLight ? "border-slate-900/[0.08] bg-white" : "border-[rgba(255,255,255,0.06)] bg-[#111827]",
        className,
      )}
    >
      <p className="text-[10px] font-semibold uppercase tracking-[0.2em] text-[#64748B]">Execution mode</p>
      <p className={cn("mt-1 text-sm", isLight ? "text-[#475569]" : "text-[#94A3B8]")}>
        Same strategy behavior - data source changes with mode.
      </p>
      <div
        role="tablist"
        aria-label="Execution mode"
        className={cn(
          "mt-4 grid grid-cols-3 gap-2 rounded-2xl border p-1.5",
          isLight ? "border-slate-900/[0.08] bg-[#F8FAFC]" : "border-[rgba(255,255,255,0.06)] bg-[#0B1220]",
        )}
      >
        {MODES.map((m) => {
          const active = value === m.id;
          const locked = m.id !== "BACKTEST";
          return (
            <motion.button
              key={m.id}
              type="button"
              role="tab"
              aria-selected={active}
              disabled={disabled || locked}
              title={locked ? "Paper and live deployment ship in a later release - backtest replay is available now." : m.hint}
              whileTap={locked ? undefined : { scale: 0.98 }}
              onClick={() => {
                if (!locked) onChange(m.id);
              }}
              className={cn(
                "relative rounded-xl px-2 py-2.5 text-center text-xs font-semibold transition-colors sm:text-sm",
                active && !locked && "bg-[#2563EB] text-white shadow-sm",
                !active && !locked && (isLight ? "text-[#475569] hover:text-[#0F172A]" : "text-[#94A3B8] hover:text-white"),
                locked && "cursor-not-allowed text-[#475569] line-through decoration-[#475569]",
                locked && active && "bg-transparent text-[#475569]",
              )}
            >
              {m.label}
            </motion.button>
          );
        })}
      </div>
    </section>
  );
}
