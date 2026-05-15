import { AnimatePresence, motion } from "framer-motion";
import { Calendar } from "lucide-react";
import { useMemo, useRef } from "react";
import { cn } from "../../../lib/utils";
import { useUiThemeStore } from "../../../state/uiTheme";

export type DateRangePreset = "1W" | "15D" | "1M" | "3M" | "6M" | "1Y";

const PRESETS: { id: DateRangePreset; label: string }[] = [
  { id: "1W", label: "1W" },
  { id: "15D", label: "15D" },
  { id: "1M", label: "1M" },
  { id: "3M", label: "3M" },
  { id: "6M", label: "6M" },
  { id: "1Y", label: "1Y" },
];

export function computePresetRange(preset: DateRangePreset, now = new Date()): { from: Date; to: Date } {
  const end = new Date(now);
  end.setSeconds(0, 0);
  const start = new Date(end);
  switch (preset) {
    case "1W":
      start.setDate(start.getDate() - 7);
      break;
    case "15D":
      start.setDate(start.getDate() - 15);
      break;
    case "1M":
      start.setMonth(start.getMonth() - 1);
      break;
    case "3M":
      start.setMonth(start.getMonth() - 3);
      break;
    case "6M":
      start.setMonth(start.getMonth() - 6);
      break;
    case "1Y":
      start.setFullYear(start.getFullYear() - 1);
      break;
    default:
      start.setMonth(start.getMonth() - 3);
  }
  return { from: start, to: end };
}

function toDatetimeLocalValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function openDatetimePicker(input: HTMLInputElement | null) {
  if (!input) return;
  try {
    input.showPicker?.();
  } catch {
    input.focus();
  }
}

type Props = {
  preset: DateRangePreset;
  onPresetChange: (p: DateRangePreset) => void;
  customOpen: boolean;
  onCustomOpenChange: (open: boolean) => void;
  from: Date;
  to: Date;
  onFromChange: (d: Date) => void;
  onToChange: (d: Date) => void;
  disabled?: boolean;
  className?: string;
};

export function DateRangeChips({
  preset,
  onPresetChange,
  customOpen,
  onCustomOpenChange,
  from,
  to,
  onFromChange,
  onToChange,
  disabled,
  className,
}: Props) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const startRef = useRef<HTMLInputElement>(null);
  const endRef = useRef<HTMLInputElement>(null);

  const rangeLabel = useMemo(() => {
    const opts: Intl.DateTimeFormatOptions = { month: "short", day: "numeric", year: "numeric" };
    return `${from.toLocaleDateString(undefined, opts)} → ${to.toLocaleDateString(undefined, opts)}`;
  }, [from, to]);

  const chipIdle = isLight
    ? "border-slate-900/[0.1] bg-[#F8FAFC] text-[#475569] hover:border-blue-500/35 hover:text-[#0F172A]"
    : "border-[rgba(255,255,255,0.08)] bg-[#0B1220] text-[#CBD5E1] hover:border-[rgba(37,99,235,0.45)] hover:text-white";

  const inputClass = cn(
    "w-full rounded-xl border px-3 py-2.5 pr-10 text-sm outline-none",
    isLight
      ? "border-slate-900/[0.1] bg-white text-[#0F172A] focus:border-[#2563EB]/80 [color-scheme:light]"
      : "border-[rgba(255,255,255,0.08)] bg-[#0B1220] text-[#F8FAFC] focus:border-[#2563EB]/80 [color-scheme:dark]",
  );

  const calBtn = isLight ? "text-[#64748B] hover:text-[#0F172A]" : "text-[#64748B] hover:text-white";

  return (
    <section
      className={cn(
        "rounded-2xl border p-5 sm:p-6 shadow-sm",
        isLight ? "border-slate-900/[0.08] bg-white" : "border-[rgba(255,255,255,0.06)] bg-[#111827]",
        className,
      )}
    >
      <p className="text-[10px] font-semibold uppercase tracking-[0.2em] text-[#64748B]">Date range</p>
      <p className={cn("mt-1 text-sm", isLight ? "text-[#475569]" : "text-[#94A3B8]")}>{rangeLabel}</p>

      <div className="mt-4 flex flex-wrap gap-2">
        {PRESETS.map((p) => {
          const active = !customOpen && preset === p.id;
          return (
            <motion.button
              key={p.id}
              type="button"
              layout
              disabled={disabled}
              whileTap={{ scale: 0.97 }}
              onClick={() => {
                onCustomOpenChange(false);
                onPresetChange(p.id);
              }}
              className={cn(
                "rounded-full border px-3.5 py-1.5 text-xs font-semibold transition-colors",
                active ? "border-[#2563EB] bg-[#2563EB] text-white shadow-sm" : chipIdle,
                disabled && "opacity-40",
              )}
            >
              {p.label}
            </motion.button>
          );
        })}
        <motion.button
          type="button"
          disabled={disabled}
          whileTap={{ scale: 0.97 }}
          onClick={() => onCustomOpenChange(!customOpen)}
          className={cn(
            "rounded-full border px-3.5 py-1.5 text-xs font-semibold transition-colors",
            customOpen ? "border-[#2563EB] bg-[#2563EB] text-white shadow-sm" : chipIdle,
            disabled && "opacity-40",
          )}
        >
          Custom
        </motion.button>
      </div>

      <AnimatePresence initial={false}>
        {customOpen ? (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
            className="overflow-hidden"
          >
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <label className="text-[11px] font-medium text-[#64748B]">
                Start (local)
                <div className="relative mt-1">
                  <input
                    ref={startRef}
                    type="datetime-local"
                    disabled={disabled}
                    value={toDatetimeLocalValue(from)}
                    onChange={(e) => onFromChange(new Date(e.target.value))}
                    className={inputClass}
                  />
                  <button
                    type="button"
                    className={cn("absolute right-2 top-1/2 -translate-y-1/2", calBtn)}
                    aria-label="Open start calendar"
                    onClick={() => openDatetimePicker(startRef.current)}
                  >
                    <Calendar className="h-4 w-4" />
                  </button>
                </div>
              </label>
              <label className="text-[11px] font-medium text-[#64748B]">
                End (local)
                <div className="relative mt-1">
                  <input
                    ref={endRef}
                    type="datetime-local"
                    disabled={disabled}
                    value={toDatetimeLocalValue(to)}
                    onChange={(e) => onToChange(new Date(e.target.value))}
                    className={inputClass}
                  />
                  <button
                    type="button"
                    className={cn("absolute right-2 top-1/2 -translate-y-1/2", calBtn)}
                    aria-label="Open end calendar"
                    onClick={() => openDatetimePicker(endRef.current)}
                  >
                    <Calendar className="h-4 w-4" />
                  </button>
                </div>
              </label>
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </section>
  );
}
