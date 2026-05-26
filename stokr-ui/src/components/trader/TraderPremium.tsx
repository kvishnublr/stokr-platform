import { motion, useReducedMotion } from "framer-motion";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { cn } from "../../lib/utils";
import { formatInr, formatPnlDisplay, pnlToneClass, type PnlDataSource } from "../../lib/moneyUtils";
import { useUiThemeStore } from "../../state/uiTheme";
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  ChevronRight,
  Radio,
  ShieldCheck,
  TrendingDown,
  TrendingUp,
} from "lucide-react";

const stagger = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.06 } },
};

const fadeUp = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: { duration: 0.35, ease: [0.22, 1, 0.36, 1] } },
};

export type CommandPositionRow = {
  symbol: string;
  side: string;
  qty: number | string | null;
  ltp?: number | string | null;
  avgPrice?: number | string | null;
  mtmPnl?: number | null;
  unrealizedPnl?: number | null;
  realizedPnl?: number | null;
  notional?: number | null;
  quantitySource?: string | null;
  parityBadge?: { label: string; tone: "rose" | "sky" | "amber" } | null;
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
    <motion.div initial="hidden" animate="show" variants={stagger} className={cn("space-y-6 pb-8", isLight ? "text-neutral-900" : "text-white")}>
      <motion.div variants={fadeUp} className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className={cn("text-[10px] font-bold uppercase tracking-[0.2em]", isLight ? "text-blue-700/80" : "text-blue-400/90")}>
            Capital & exposure
          </p>
          <h1 className={cn("mt-1 text-2xl font-black tracking-tight", isLight ? "text-neutral-900" : "text-white")}>{title}</h1>
          {subtitle ? <div className={cn("mt-1.5 max-w-2xl text-sm leading-relaxed", isLight ? "text-neutral-500" : "text-neutral-400")}>{subtitle}</div> : null}
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
    source === "BROKER" ? "Broker-verified" : source === "OMS" ? "OMS ledger" : source === "POSITIONS" ? "Position roll-up" : source === "WORKSTATION" ? "Workstation" : "Awaiting sync";
  const brokerOk = source === "BROKER" && brokerConnected !== false;
  const cls = brokerOk
    ? isLight ? "border-emerald-300/70 bg-emerald-50/90 text-emerald-800" : "border-emerald-500/35 bg-emerald-500/10 text-emerald-300"
    : isLight ? "border-amber-300/70 bg-amber-50/90 text-amber-900" : "border-amber-500/35 bg-amber-500/10 text-amber-200";

  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[10px] font-bold uppercase tracking-[0.14em]", cls)}>
      {brokerOk ? <ShieldCheck className="h-3 w-3" /> : <Radio className="h-3 w-3" />}
      P&amp;L · {label}
    </span>
  );
}

function PnlSpark({ positive, isLight }: { positive: boolean; isLight: boolean }) {
  const bars = positive ? [0.35, 0.55, 0.45, 0.72, 0.9] : [0.9, 0.62, 0.48, 0.35, 0.28];
  return (
    <div className="flex h-8 items-end gap-0.5">
      {bars.map((h, i) => (
        <motion.div
          key={i}
          initial={{ height: 0 }}
          animate={{ height: `${h * 100}%` }}
          transition={{ delay: 0.08 * i, duration: 0.35 }}
          className={cn("w-1 rounded-full", positive ? (isLight ? "bg-emerald-400/80" : "bg-emerald-400") : isLight ? "bg-rose-400/80" : "bg-rose-400")}
        />
      ))}
    </div>
  );
}

function PnlMetricCell({
  label,
  value,
  pnlValue,
  loading,
  sublabel,
  tone,
  icon: Icon,
  isLight,
  index,
}: {
  label: string;
  value: string;
  pnlValue?: number | null;
  loading?: boolean;
  sublabel?: ReactNode;
  tone: "emerald" | "sky" | "violet" | "amber" | "neutral";
  icon: React.ElementType;
  isLight: boolean;
  index: number;
}) {
  const reduceMotion = useReducedMotion();
  const positive = pnlValue != null ? pnlValue >= 0 : true;
  const toneMap = {
    emerald: isLight ? "from-emerald-500/15 via-emerald-500/5 to-transparent" : "from-emerald-500/20 via-emerald-500/5 to-transparent",
    sky: isLight ? "from-sky-500/15 via-sky-500/5 to-transparent" : "from-sky-500/20 via-sky-500/5 to-transparent",
    violet: isLight ? "from-violet-500/15 via-violet-500/5 to-transparent" : "from-violet-500/20 via-violet-500/5 to-transparent",
    amber: isLight ? "from-amber-500/15 via-amber-500/5 to-transparent" : "from-amber-500/20 via-amber-500/5 to-transparent",
    neutral: isLight ? "from-neutral-500/10 via-neutral-500/5 to-transparent" : "from-neutral-500/15 via-neutral-500/5 to-transparent",
  };

  return (
    <motion.div
      variants={fadeUp}
      custom={index}
      whileHover={reduceMotion ? undefined : { y: -2 }}
      className={cn(
        "relative min-w-0 flex-1 overflow-hidden rounded-2xl border p-4 sm:p-5",
        isLight ? "border-neutral-200/80 bg-white shadow-[0_20px_50px_-40px_rgba(15,23,42,0.35)]" : "border-white/[0.08] bg-neutral-950/70 shadow-[0_20px_50px_-40px_rgba(0,0,0,0.65)]",
      )}
    >
      <div className={cn("pointer-events-none absolute inset-0 bg-gradient-to-br opacity-90", toneMap[tone])} />
      <motion.div
        aria-hidden
        className={cn("pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent to-transparent",
          tone === "emerald" ? "via-emerald-400/70" : tone === "sky" ? "via-sky-400/70" : tone === "violet" ? "via-violet-400/70" : tone === "amber" ? "via-amber-400/70" : "via-neutral-400/50")}
        animate={reduceMotion ? undefined : { opacity: [0.35, 0.9, 0.35] }}
        transition={{ duration: 3.2, repeat: Infinity, ease: "easeInOut", delay: index * 0.2 }}
      />
      <div className="relative flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-neutral-500" : "text-neutral-400")}>{label}</p>
          {loading ? (
            <div className={cn("mt-3 h-9 w-36 animate-pulse rounded-lg", isLight ? "bg-neutral-100" : "bg-white/10")} />
          ) : (
            <div className="mt-2 flex items-end gap-2">
              <span className={cn("text-[clamp(1.35rem,2.4vw,1.85rem)] font-black tabular-nums tracking-tight", pnlToneClass(pnlValue, isLight))}>{value}</span>
              {pnlValue != null ? (
                positive ? <ArrowUpRight className="mb-1 h-4 w-4 text-emerald-500" /> : <ArrowDownRight className="mb-1 h-4 w-4 text-rose-500" />
              ) : null}
            </div>
          )}
          {sublabel ? <p className={cn("mt-2 text-[11px] font-medium", isLight ? "text-neutral-500" : "text-neutral-400")}>{sublabel}</p> : null}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-2">
          <div className={cn("rounded-xl border p-2", isLight ? "border-neutral-200/80 bg-white/70" : "border-white/10 bg-white/5")}>
            <Icon className={cn("h-4 w-4", isLight ? "text-neutral-600" : "text-neutral-300")} />
          </div>
          {pnlValue != null && !loading ? <PnlSpark positive={positive} isLight={isLight} /> : null}
        </div>
      </div>
    </motion.div>
  );
}

/** Unified institutional P&L command rail — replaces generic KPI cards. */
export function PnlCommandRail({
  mtm,
  unrealized,
  realized,
  loading,
  openPositions,
  sublabel,
}: {
  mtm: number | null;
  unrealized: number | null;
  realized: number | null;
  loading?: boolean;
  openPositions?: number | null;
  sublabel?: ReactNode;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const posLabel = openPositions != null && openPositions > 0 ? `${openPositions} open position${openPositions === 1 ? "" : "s"}` : sublabel;

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-3 md:grid-cols-3">
      <PnlMetricCell label="MTM P&L" value={formatPnlDisplay(mtm)} pnlValue={mtm} loading={loading} sublabel={posLabel} tone={(mtm ?? 0) >= 0 ? "emerald" : "emerald"} icon={TrendingUp} isLight={isLight} index={0} />
      <PnlMetricCell label="Unrealized P&L" value={formatPnlDisplay(unrealized)} pnlValue={unrealized} loading={loading} tone="sky" icon={Activity} isLight={isLight} index={1} />
      <PnlMetricCell label="Realized P&L" value={formatPnlDisplay(realized)} pnlValue={realized} loading={loading} tone="violet" icon={TrendingDown} isLight={isLight} index={2} />
    </motion.div>
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
  const tone = accent?.includes("rose") ? "emerald" : accent?.includes("sky") ? "sky" : accent?.includes("violet") ? "violet" : accent?.includes("amber") ? "amber" : "neutral";
  return (
    <PnlMetricCell label={label} value={value} pnlValue={pnlValue} loading={loading} sublabel={sublabel} tone={tone} icon={Icon} isLight={isLight} index={index} />
  );
}

function fmtQty(v: unknown) {
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 }).format(n) : String(v ?? "—");
}

function fmtPrice(v: unknown) {
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : "—";
}

/** Institutional live positions table — dashboard + positions page. */
export function LivePositionsCommandTable({
  title = "Live positions",
  subtitle,
  rows,
  loading,
  action,
  footerMtm,
  showExtendedColumns = false,
}: {
  title?: string;
  subtitle?: string;
  rows: CommandPositionRow[];
  loading?: boolean;
  action?: ReactNode;
  footerMtm?: number | null;
  showExtendedColumns?: boolean;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const reduceMotion = useReducedMotion();
  const maxAbsMtm = Math.max(...rows.map((r) => Math.abs(Number(r.mtmPnl ?? 0))), 1);

  return (
    <motion.div
      variants={fadeUp}
      className={cn(
        "overflow-hidden rounded-2xl border",
        isLight ? "border-neutral-200/80 bg-white shadow-[0_24px_60px_-44px_rgba(15,23,42,0.35)]" : "border-white/[0.08] bg-neutral-950/75 shadow-[0_24px_60px_-44px_rgba(0,0,0,0.75)]",
      )}
    >
      <div className={cn("flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3.5 sm:px-5", isLight ? "border-neutral-100 bg-gradient-to-r from-neutral-50/90 to-white" : "border-white/[0.06] bg-gradient-to-r from-neutral-900/80 to-neutral-950/40")}>
        <div className="flex items-center gap-3">
          <span className="relative flex h-2.5 w-2.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-35" />
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-emerald-500" />
          </span>
          <div>
            <h3 className={cn("text-sm font-bold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>{title}</h3>
            <p className={cn("text-[11px]", isLight ? "text-neutral-500" : "text-neutral-400")}>
              {subtitle ?? `${rows.length} symbol${rows.length === 1 ? "" : "s"} · broker-backed quantities`}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {footerMtm != null ? (
            <div className={cn("rounded-xl border px-3 py-1.5", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-white/5")}>
              <span className={cn("text-[10px] font-bold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>Session MTM </span>
              <span className={cn("ml-1 font-mono text-sm font-black tabular-nums", pnlToneClass(footerMtm, isLight))}>{formatPnlDisplay(footerMtm)}</span>
            </div>
          ) : null}
          {action}
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="min-w-full text-left text-xs">
          <thead>
            <tr className={cn("text-[10px] uppercase tracking-[0.16em]", isLight ? "bg-neutral-900 text-neutral-300" : "bg-black/40 text-neutral-400")}>
              {(showExtendedColumns
                ? ["Symbol", "MTM P&L", "Side", "Qty", "Avg", "LTP", "Unrealized", "Realized", "Notional", "Source"]
                : ["Symbol", "Side", "Qty", "LTP", "MTM P&L", "Unrealized"]
              ).map((h) => (
                <th key={h} className="px-3 py-3 font-bold first:pl-5 last:pr-5">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={showExtendedColumns ? 10 : 6} className="px-5 py-10 text-center text-neutral-500">Syncing positions…</td>
              </tr>
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={showExtendedColumns ? 10 : 6}>
                  <EmptyState message="No open positions in this lane" />
                </td>
              </tr>
            ) : (
              rows.map((r, i) => {
                const mtm = r.mtmPnl != null ? Number(r.mtmPnl) : null;
                const barPct = mtm != null ? Math.min(100, (Math.abs(mtm) / maxAbsMtm) * 100) : 0;
                return (
                  <motion.tr
                    key={`${r.symbol}-${i}`}
                    initial={reduceMotion ? false : { opacity: 0, x: -8 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: i * 0.03, duration: 0.28 }}
                    className={cn(
                      "group border-t transition-colors",
                      isLight ? "border-neutral-100 hover:bg-sky-500/[0.04]" : "border-white/[0.05] hover:bg-sky-500/[0.06]",
                    )}
                  >
                    <td className="px-3 py-3.5 first:pl-5">
                      <div className="flex items-center gap-2">
                        <div className={cn("h-8 w-1 rounded-full", (mtm ?? 0) >= 0 ? "bg-emerald-500" : "bg-rose-500")} style={{ opacity: 0.35 + barPct / 200 }} />
                        <div>
                          <div className={cn("font-mono text-sm font-black tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
                            {String(r.symbol).replace(/^NSE:/, "")}
                          </div>
                          {r.parityBadge ? (
                            <span className={cn("mt-1 inline-block rounded px-1.5 py-0.5 text-[9px] font-bold uppercase",
                              r.parityBadge.tone === "rose" ? "bg-rose-500/15 text-rose-600" : r.parityBadge.tone === "sky" ? "bg-sky-500/15 text-sky-700" : "bg-amber-500/15 text-amber-700",
                            )}>{r.parityBadge.label}</span>
                          ) : null}
                        </div>
                      </div>
                    </td>
                    {showExtendedColumns ? (
                      <td className="px-3 py-3.5">
                        <div className="space-y-1.5">
                          <PnlCell value={r.mtmPnl} />
                          <div className={cn("h-1 overflow-hidden rounded-full", isLight ? "bg-neutral-100" : "bg-neutral-800")}>
                            <motion.div className={cn("h-full rounded-full", (mtm ?? 0) >= 0 ? "bg-emerald-500" : "bg-rose-500")} initial={{ width: 0 }} animate={{ width: `${barPct}%` }} transition={{ duration: 0.5, delay: i * 0.04 }} />
                          </div>
                        </div>
                      </td>
                    ) : null}
                    <td className="px-3 py-3.5"><SideBadge side={r.side} /></td>
                    <td className={cn("px-3 py-3.5 font-mono font-bold tabular-nums", isLight ? "text-neutral-800" : "text-neutral-100")}>{fmtQty(r.qty)}</td>
                    {showExtendedColumns ? (
                      <>
                        <td className="px-3 py-3.5 font-mono tabular-nums">{fmtPrice(r.avgPrice)}</td>
                        <td className="px-3 py-3.5 font-mono tabular-nums">{fmtPrice(r.ltp)}</td>
                        <td className="px-3 py-3.5"><PnlCell value={r.unrealizedPnl} /></td>
                        <td className="px-3 py-3.5"><PnlCell value={r.realizedPnl} /></td>
                        <td className="px-3 py-3.5 font-mono tabular-nums">{r.notional != null ? formatInr(r.notional).replace(/^\+/, "") : "—"}</td>
                        <td className="px-3 py-3.5 last:pr-5">
                          <span className={cn("rounded-md px-2 py-0.5 text-[10px] font-bold uppercase", r.quantitySource === "BROKER" ? "bg-emerald-500/15 text-emerald-700" : "bg-neutral-500/10 text-neutral-600")}>
                            {r.quantitySource === "BROKER" ? "ZERODHA" : (r.quantitySource ?? "OMS")}
                          </span>
                        </td>
                      </>
                    ) : (
                      <>
                        <td className="px-3 py-3.5 font-mono tabular-nums">{fmtPrice(r.ltp)}</td>
                        <td className="px-3 py-3.5">
                          <div className="space-y-1.5">
                            <PnlCell value={r.mtmPnl} />
                            <div className={cn("h-1 overflow-hidden rounded-full", isLight ? "bg-neutral-100" : "bg-neutral-800")}>
                              <motion.div className={cn("h-full rounded-full", (mtm ?? 0) >= 0 ? "bg-emerald-500" : "bg-rose-500")} initial={{ width: 0 }} animate={{ width: `${barPct}%` }} transition={{ duration: 0.5, delay: i * 0.04 }} />
                            </div>
                          </div>
                        </td>
                        <td className="px-3 py-3.5 last:pr-5"><PnlCell value={r.unrealizedPnl} /></td>
                      </>
                    )}
                  </motion.tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
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
    <span className={cn("inline-flex rounded-md px-1.5 py-0.5 font-mono text-[11px] font-bold tabular-nums",
      parsed == null ? "text-neutral-400" :
      parsed >= 0 ? (isLight ? "bg-emerald-500/10 text-emerald-700" : "bg-emerald-500/15 text-emerald-300") :
      (isLight ? "bg-rose-500/10 text-rose-700" : "bg-rose-500/15 text-rose-300"),
    )}>
      {formatPnlDisplay(parsed)}
    </span>
  );
}

export function SideBadge({ side }: { side: string }) {
  const s = side.toUpperCase();
  const long = s.includes("BUY") || s.includes("LONG");
  return (
    <span className={cn(
      "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-black uppercase tracking-wide",
      long ? "border-emerald-500/25 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300" : "border-rose-500/25 bg-rose-500/10 text-rose-700 dark:text-rose-300",
    )}>
      <span className={cn("h-1.5 w-1.5 rounded-full", long ? "bg-emerald-500" : "bg-rose-500")} />
      {long ? "Long" : "Short"}
    </span>
  );
}

export function StatChip({ label, value, tone = "neutral" }: { label: string; value: string; tone?: "neutral" | "good" | "bad" | "warn" }) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const toneCls =
    tone === "good" ? isLight ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-emerald-500/30 bg-emerald-500/10 text-emerald-300"
    : tone === "bad" ? isLight ? "border-rose-200 bg-rose-50 text-rose-800" : "border-rose-500/30 bg-rose-500/10 text-rose-300"
    : tone === "warn" ? isLight ? "border-amber-200 bg-amber-50 text-amber-800" : "border-amber-500/30 bg-amber-500/10 text-amber-200"
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

export function TerminalLinkAction({ to, label = "Terminal" }: { to: string; label?: string }) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <Link
      to={to}
      className={cn(
        "inline-flex items-center gap-1 rounded-xl border px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide transition hover:-translate-y-0.5",
        isLight ? "border-sky-200 bg-sky-50 text-sky-800 hover:bg-sky-100" : "border-sky-500/30 bg-sky-500/10 text-sky-300",
      )}
    >
      {label} <ChevronRight className="h-3.5 w-3.5" />
    </Link>
  );
}

export { stagger, fadeUp };
