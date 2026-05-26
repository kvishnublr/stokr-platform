import { motion } from "framer-motion";
import type { ReactNode } from "react";
import { cn } from "../../lib/utils";
import { formatPnlDisplay, pnlToneClass, type PnlDataSource } from "../../lib/moneyUtils";
import { useUiThemeStore } from "../../state/uiTheme";
import { Activity, Radio, ShieldCheck, TrendingUp } from "lucide-react";

const stagger = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.06 } },
};

const fadeUp = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: { duration: 0.35, ease: [0.22, 1, 0.36, 1] } },
};

export function TraderPageShell({
  title,
  subtitle,
  actions,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <motion.div
      initial="hidden"
      animate="show"
      variants={stagger}
      className={cn("space-y-6 pb-8", isLight ? "text-neutral-900" : "text-white")}
    >
      <motion.div variants={fadeUp} className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className={cn("text-2xl font-black tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
            {title}
          </h1>
          {subtitle ? (
            <div className={cn("mt-1.5 text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>{subtitle}</div>
          ) : null}
        </div>
        {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
      </motion.div>
      {children}
    </motion.div>
  );
}

export function PnlSourceBadge({ source, brokerConnected }: { source: PnlDataSource; brokerConnected?: boolean }) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const label =
    source === "BROKER"
      ? "Zerodha broker figures"
      : source === "OMS"
        ? "OMS ledger"
        : source === "POSITIONS"
          ? "Position roll-up"
          : source === "WORKSTATION"
            ? "Workstation snapshot"
            : "Awaiting data";

  const brokerOk = source === "BROKER" && brokerConnected !== false;
  const cls = brokerOk
    ? isLight
      ? "border-emerald-200 bg-emerald-50 text-emerald-800"
      : "border-emerald-500/30 bg-emerald-500/10 text-emerald-300"
    : isLight
      ? "border-amber-200 bg-amber-50 text-amber-800"
      : "border-amber-500/30 bg-amber-500/10 text-amber-200";

  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[10px] font-bold uppercase tracking-wide", cls)}>
      {brokerOk ? <ShieldCheck className="h-3 w-3" /> : <Radio className="h-3 w-3" />}
      P&amp;L · {label}
    </span>
  );
}

export function AnimatedKpiCard({
  label,
  value,
  sublabel,
  loading,
  pnlValue,
  accent,
  icon: Icon = TrendingUp,
  index = 0,
}: {
  label: string;
  value: string;
  sublabel?: ReactNode;
  loading?: boolean;
  pnlValue?: number | null;
  accent?: string;
  icon?: React.ElementType;
  index?: number;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <motion.div
      variants={fadeUp}
      custom={index}
      whileHover={{ y: -2, transition: { duration: 0.2 } }}
      className={cn(
        "group relative overflow-hidden rounded-2xl border p-5 shadow-sm transition-shadow hover:shadow-lg",
        isLight ? "border-neutral-200/80 bg-white" : "border-white/[0.08] bg-neutral-900/70",
      )}
    >
      <div className={cn("absolute inset-x-0 top-0 h-0.5 opacity-80", accent ?? "bg-sky-400")} />
      <div className="flex items-start justify-between gap-2">
        <span className={cn("text-[11px] font-bold uppercase tracking-widest", isLight ? "text-neutral-400" : "text-neutral-500")}>
          {label}
        </span>
        <div className={cn("rounded-xl p-2 transition group-hover:scale-105", isLight ? "bg-neutral-100" : "bg-white/[0.06]")}>
          <Icon className={cn("h-4 w-4", isLight ? "text-neutral-500" : "text-neutral-400")} />
        </div>
      </div>
      <div className="mt-3">
        {loading ? (
          <div className={cn("h-8 w-32 animate-pulse rounded-lg", isLight ? "bg-neutral-100" : "bg-white/10")} />
        ) : (
          <div className={cn("text-2xl font-black tabular-nums tracking-tight", pnlToneClass(pnlValue, isLight))}>
            {value}
          </div>
        )}
      </div>
      {sublabel ? (
        <div className={cn("mt-2 text-xs font-medium", isLight ? "text-neutral-500" : "text-neutral-400")}>{sublabel}</div>
      ) : null}
    </motion.div>
  );
}

export function PremiumPanel({
  title,
  action,
  children,
  className,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <motion.div
      variants={fadeUp}
      className={cn(
        "overflow-hidden rounded-2xl border shadow-sm",
        isLight ? "border-neutral-200/80 bg-white" : "border-white/[0.08] bg-neutral-900/60",
        className,
      )}
    >
      <div className={cn("flex items-center justify-between border-b px-5 py-3.5", isLight ? "border-neutral-100" : "border-white/[0.06]")}>
        <h3 className={cn("text-sm font-bold", isLight ? "text-neutral-900" : "text-white")}>{title}</h3>
        {action}
      </div>
      <div className="p-1">{children}</div>
    </motion.div>
  );
}

export function PnlCell({ value }: { value: unknown }) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const n = typeof value === "number" ? value : Number(value);
  const parsed = Number.isFinite(n) ? n : null;
  return (
    <span className={cn("font-mono font-semibold tabular-nums", pnlToneClass(parsed, isLight))}>
      {formatPnlDisplay(parsed)}
    </span>
  );
}

export function SideBadge({ side }: { side: string }) {
  const s = side.toUpperCase();
  const long = s.includes("BUY") || s.includes("LONG");
  return (
    <span
      className={cn(
        "rounded-md px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide",
        long ? "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300" : "bg-rose-500/15 text-rose-700 dark:text-rose-300",
      )}
    >
      {s || "—"}
    </span>
  );
}

export function StatChip({ label, value, tone = "neutral" }: { label: string; value: string; tone?: "neutral" | "good" | "bad" | "warn" }) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const toneCls =
    tone === "good"
      ? isLight ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-emerald-500/30 bg-emerald-500/10 text-emerald-300"
      : tone === "bad"
        ? isLight ? "border-rose-200 bg-rose-50 text-rose-800" : "border-rose-500/30 bg-rose-500/10 text-rose-300"
        : tone === "warn"
          ? isLight ? "border-amber-200 bg-amber-50 text-amber-800" : "border-amber-500/30 bg-amber-500/10 text-amber-200"
          : isLight ? "border-neutral-200 bg-neutral-50 text-neutral-700" : "border-white/10 bg-white/5 text-neutral-300";
  return (
    <div className={cn("rounded-xl border px-3 py-2", toneCls)}>
      <div className="text-[10px] font-semibold uppercase tracking-wide opacity-70">{label}</div>
      <div className="mt-0.5 font-mono text-sm font-bold tabular-nums">{value}</div>
    </div>
  );
}

export function EmptyState({ message, icon: Icon = Activity }: { message: string; icon?: React.ElementType }) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <div className={cn("flex flex-col items-center justify-center gap-2 py-12 text-center", isLight ? "text-neutral-400" : "text-neutral-500")}>
      <Icon className="h-8 w-8 opacity-40" />
      <p className="text-sm font-medium">{message}</p>
    </div>
  );
}

export { stagger, fadeUp };
