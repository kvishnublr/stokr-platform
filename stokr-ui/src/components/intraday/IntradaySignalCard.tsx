import { motion } from "framer-motion";
import { Activity, TrendingDown, TrendingUp } from "lucide-react";
import { cn } from "../../lib/utils";
import { fmtTime } from "../../lib/dateUtils";
import { bareSymbol, formatConfidencePct, signalDirection, signalStrategyKey } from "../../lib/intradaySignals";
import { formatInr, parseMoney } from "../../lib/moneyUtils";

type Props = {
  row: Record<string, unknown>;
  ltp: number | null;
  index: number;
  isLight: boolean;
};

function fmt(v: unknown, d = 2) {
  if (v == null) return "—";
  const n = typeof v === "number" ? v : parseFloat(String(v));
  if (Number.isNaN(n)) return String(v);
  return n.toFixed(d);
}

export function IntradaySignalCard({ row, ltp, index, isLight }: Props) {
  const dir = signalDirection(row);
  const isBuy = dir === "BUY";
  const isSell = dir === "SELL";
  const entry = parseMoney(row.entryReferencePrice);
  const apiLtp = parseMoney(row.ltp);
  const liveLtp = ltp ?? apiLtp;
  const ltpVsEntry = liveLtp != null && entry != null && entry !== 0 ? ((liveLtp - entry) / entry) * 100 : null;
  const conf = formatConfidencePct(row.confidenceScore ?? row.confidence);
  const rr = fmt(row.riskReward, 1);
  const createdAt = String(row.createdAt ?? "");

  return (
    <motion.article
      layout
      initial={{ opacity: 0, y: 16, scale: 0.97 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, scale: 0.96 }}
      transition={{ type: "spring", stiffness: 320, damping: 28, delay: Math.min(index * 0.035, 0.35) }}
      whileHover={{ y: -6, transition: { duration: 0.2 } }}
      className={cn(
        "group relative overflow-hidden rounded-2xl border p-4 shadow-sm transition-shadow hover:shadow-xl",
        isBuy
          ? isLight
            ? "border-emerald-200/90 bg-gradient-to-br from-white via-emerald-50/40 to-sky-50/30 hover:shadow-emerald-500/10"
            : "border-emerald-500/30 bg-gradient-to-br from-neutral-950 via-emerald-950/20 to-neutral-900 hover:shadow-emerald-500/20"
          : isSell
            ? isLight
              ? "border-rose-200/90 bg-gradient-to-br from-white via-rose-50/40 to-orange-50/20 hover:shadow-rose-500/10"
              : "border-rose-500/30 bg-gradient-to-br from-neutral-950 via-rose-950/20 to-neutral-900 hover:shadow-rose-500/20"
            : isLight
              ? "border-slate-200/90 bg-white/80"
              : "border-neutral-800 bg-neutral-950/80",
      )}
    >
      <motion.div
        aria-hidden
        className={cn("pointer-events-none absolute -right-10 -top-10 h-32 w-32 rounded-full blur-3xl", isBuy ? "bg-emerald-400/25" : isSell ? "bg-rose-400/25" : "bg-indigo-400/20")}
        animate={{ scale: [1, 1.15, 1], opacity: [0.35, 0.55, 0.35] }}
        transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }}
      />
      <motion.div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-white/80 to-transparent opacity-0 group-hover:opacity-100"
        initial={false}
        animate={{ x: ["-100%", "100%"] }}
        transition={{ duration: 1.8, repeat: Infinity, ease: "linear" }}
      />

      <div className="relative flex items-start justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <span className={cn("font-mono text-lg font-bold tracking-tight", isLight ? "text-slate-900" : "text-white")}>
              {bareSymbol(row.symbol)}
            </span>
            <span
              className={cn(
                "inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wider",
                isBuy
                  ? "bg-emerald-500 text-white shadow-sm shadow-emerald-500/30"
                  : isSell
                    ? "bg-rose-500 text-white shadow-sm shadow-rose-500/30"
                    : isLight
                      ? "bg-slate-200 text-slate-600"
                      : "bg-neutral-800 text-neutral-300",
              )}
            >
              {isBuy ? <TrendingUp className="h-3 w-3" /> : isSell ? <TrendingDown className="h-3 w-3" /> : null}
              {dir}
            </span>
          </div>
          <p className={cn("mt-1 truncate text-[11px] font-medium", isLight ? "text-slate-500" : "text-neutral-400")}>
            {signalStrategyKey(row)}
          </p>
        </div>
        <div className="text-right">
          <div className={cn("text-[10px] font-semibold uppercase tracking-wider", isLight ? "text-slate-400" : "text-neutral-500")}>LTP</div>
          <motion.div
            key={liveLtp ?? "na"}
            initial={{ opacity: 0.6, scale: 0.98 }}
            animate={{ opacity: 1, scale: 1 }}
            className={cn("font-mono text-base font-bold tabular-nums", isLight ? "text-sky-700" : "text-sky-300")}
          >
            {formatInr(liveLtp)}
          </motion.div>
          {ltpVsEntry != null ? (
            <div className={cn("text-[10px] font-semibold tabular-nums", ltpVsEntry >= 0 ? "text-emerald-600" : "text-rose-600")}>
              {ltpVsEntry >= 0 ? "+" : ""}
              {ltpVsEntry.toFixed(2)}% vs entry
            </div>
          ) : null}
        </div>
      </div>

      <div className={cn("relative mt-4 grid grid-cols-4 gap-2 rounded-xl border p-2.5", isLight ? "border-slate-100 bg-white/60" : "border-neutral-800 bg-neutral-900/50")}>
        {[
          { label: "Entry", value: fmt(row.entryReferencePrice), tone: isLight ? "text-slate-800" : "text-neutral-100" },
          { label: "SL", value: fmt(row.stopPrice), tone: "text-rose-600" },
          { label: "Target", value: fmt(row.targetPrice), tone: "text-emerald-600" },
          { label: "RR", value: `${rr}x`, tone: isLight ? "text-indigo-700" : "text-indigo-300" },
        ].map(({ label, value, tone }) => (
          <div key={label} className="text-center">
            <div className={cn("text-[9px] font-bold uppercase tracking-wider opacity-60")}>{label}</div>
            <div className={cn("mt-0.5 font-mono text-xs font-semibold tabular-nums", tone)}>{value}</div>
          </div>
        ))}
      </div>

      <div className="relative mt-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Activity className={cn("h-3.5 w-3.5", isLight ? "text-indigo-500" : "text-indigo-400")} />
          <span className={cn("rounded-md px-2 py-0.5 text-[10px] font-bold", isLight ? "bg-indigo-100 text-indigo-800" : "bg-indigo-500/20 text-indigo-200")}>
            {conf}
          </span>
          <span className={cn("text-[10px] tabular-nums", isLight ? "text-slate-400" : "text-neutral-500")}>{fmtTime(createdAt)}</span>
        </div>
        <motion.span
          className={cn("h-1.5 w-1.5 rounded-full", isBuy ? "bg-emerald-500" : "bg-rose-500")}
          animate={{ opacity: [0.4, 1, 0.4] }}
          transition={{ duration: 1.5, repeat: Infinity }}
        />
      </div>
    </motion.article>
  );
}
