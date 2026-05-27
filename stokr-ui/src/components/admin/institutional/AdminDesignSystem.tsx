import type { ReactNode } from "react";
import { motion } from "framer-motion";
import { cn } from "../../../lib/utils";
import { toneChipClasses, type StatusTone } from "../../../lib/statusTone";
import { fadeUp, staggerContainer } from "../../../lib/motionPresets";
import { GlassPanel } from "../../ds/GlassPanel";

export const adminFadeUp = fadeUp;
export const adminStagger = staggerContainer;

export function AdminPageShell({
  title,
  subtitle,
  eyebrow,
  actions,
  alert,
  children,
  isLight,
  dense,
}: {
  title: string;
  subtitle?: ReactNode;
  eyebrow?: string;
  actions?: ReactNode;
  alert?: ReactNode;
  children: ReactNode;
  isLight: boolean;
  dense?: boolean;
}) {
  return (
    <motion.div
      initial="hidden"
      animate="show"
      variants={adminStagger}
      className={cn("flex min-h-0 flex-1 flex-col", dense ? "space-y-5" : "space-y-8")}
    >
      <motion.header variants={adminFadeUp} className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          {eyebrow ? (
            <p
              className={cn(
                "text-[11px] font-bold uppercase tracking-[0.18em]",
                isLight ? "text-blue-700" : "text-blue-400/90",
              )}
            >
              {eyebrow}
            </p>
          ) : null}
          <h1
            className={cn(
              "mt-1 text-[clamp(1.75rem,3vw,2.25rem)] font-semibold tracking-tight",
              isLight
                ? "text-neutral-900"
                : "bg-gradient-to-br from-white via-white to-neutral-400 bg-clip-text text-transparent",
            )}
          >
            {title}
          </h1>
          {subtitle ? (
            <p className={cn("mt-2 max-w-3xl text-sm leading-relaxed", isLight ? "text-neutral-600" : "text-neutral-400")}>
              {subtitle}
            </p>
          ) : null}
        </div>
        {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </motion.header>

      {alert ? (
        <motion.div variants={adminFadeUp}>{alert}</motion.div>
      ) : null}

      <motion.div variants={adminFadeUp} className="min-h-0 flex-1">
        {children}
      </motion.div>
    </motion.div>
  );
}

export function AdminSection({
  title,
  subtitle,
  action,
  children,
  isLight,
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
  children: ReactNode;
  isLight: boolean;
}) {
  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className={cn("text-sm font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-neutral-100")}>
            {title}
          </h2>
          {subtitle ? (
            <p className={cn("mt-0.5 text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>{subtitle}</p>
          ) : null}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

export function AdminPanel({
  title,
  subtitle,
  children,
  isLight,
  className,
  accent,
  noPadding,
}: {
  title?: string;
  subtitle?: string;
  children: ReactNode;
  isLight: boolean;
  className?: string;
  accent?: boolean;
  noPadding?: boolean;
}) {
  return (
    <GlassPanel
      variant={isLight ? "light" : "dark"}
      accent={accent}
      interactive
      className={cn(noPadding ? "overflow-hidden p-0" : "p-5", className)}
    >
      {title ? (
        <div className={cn("mb-4", noPadding && "border-b px-5 py-4", isLight ? "border-neutral-200" : "border-neutral-800")}>
          <h3 className={cn("text-sm font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>{title}</h3>
          {subtitle ? (
            <p className={cn("mt-0.5 text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>{subtitle}</p>
          ) : null}
        </div>
      ) : null}
      <div className={noPadding && title ? "px-5 pb-5" : undefined}>{children}</div>
    </GlassPanel>
  );
}

export function AdminPulseDot({ live, tone = "ok" }: { live?: boolean; tone?: "ok" | "warn" | "bad" }) {
  const color =
    tone === "ok" ? "bg-emerald-400" : tone === "warn" ? "bg-amber-400" : "bg-rose-400";
  return (
    <motion.span
      animate={live ? { opacity: [0.4, 1, 0.4], scale: [1, 1.2, 1] } : {}}
      transition={{ duration: 2, repeat: Infinity }}
      className={cn("inline-block h-2 w-2 rounded-full ring-2 ring-black/10 dark:ring-white/10", color)}
    />
  );
}

export function AdminHeatCell({
  label,
  value,
  intensity,
  isLight,
}: {
  label: string;
  value: string;
  intensity: number;
  isLight: boolean;
}) {
  const t = Math.max(0, Math.min(1, intensity));
  return (
    <div
      className={cn(
        "rounded-lg border px-2 py-2 text-center transition-colors",
        isLight ? "border-neutral-200" : "border-neutral-800",
      )}
      style={{
        background: isLight
          ? `rgba(59, 130, 246, ${0.04 + t * 0.18})`
          : `rgba(59, 130, 246, ${0.08 + t * 0.28})`,
      }}
    >
      <div className={cn("truncate text-[10px] font-medium uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>
        {label}
      </div>
      <div className={cn("mt-1 font-mono text-xs font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>
        {value}
      </div>
    </div>
  );
}

export function AdminEmptyState({
  title,
  detail,
  action,
  isLight,
}: {
  title: string;
  detail?: string;
  action?: ReactNode;
  isLight: boolean;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center rounded-2xl border border-dashed px-6 py-14 text-center",
        isLight ? "border-neutral-300 bg-neutral-50/80" : "border-neutral-800 bg-neutral-950/40",
      )}
    >
      <p className={cn("text-sm font-semibold", isLight ? "text-neutral-800" : "text-neutral-200")}>{title}</p>
      {detail ? (
        <p className={cn("mt-2 max-w-md text-xs leading-relaxed", isLight ? "text-neutral-500" : "text-neutral-500")}>
          {detail}
        </p>
      ) : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

export function AdminStatusChip({
  tone,
  isLight,
  children,
  className,
}: {
  tone: StatusTone;
  isLight: boolean;
  children: ReactNode;
  className?: string;
}) {
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[10px] font-semibold", toneChipClasses(isLight, tone), className)}>
      {children}
    </span>
  );
}

export function AdminSkeletonGrid({ cols = 4, isLight }: { cols?: number; isLight: boolean }) {
  return (
    <div className={cn("grid gap-4", cols === 3 ? "grid-cols-1 md:grid-cols-3" : "grid-cols-2 lg:grid-cols-4")}>
      {Array.from({ length: cols }).map((_, i) => (
        <div
          key={i}
          className={cn(
            "h-28 animate-pulse rounded-2xl",
            isLight ? "bg-neutral-200/70" : "bg-neutral-800/70",
          )}
        />
      ))}
    </div>
  );
}
