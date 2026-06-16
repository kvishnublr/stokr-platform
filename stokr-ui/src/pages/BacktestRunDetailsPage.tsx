import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle } from "lucide-react";
import { Link, useParams } from "react-router-dom";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { toast } from "sonner";
import { getRunDetail, resumeRun } from "../api/backtest";
import { parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

function fmtNum(v: string | number | undefined, digits = 4) {
  if (v === undefined || v === null) return "-";
  const n = typeof v === "number" ? v : Number(v);
  if (!Number.isFinite(n)) return String(v);
  return n.toFixed(digits);
}

function NoFillsEmptyState({
  symbol,
  isLight,
  strategySignalCount,
  executionEventCount,
}: {
  symbol: string;
  isLight: boolean;
  strategySignalCount?: number;
  executionEventCount?: number;
}) {
  const sig = strategySignalCount ?? 0;
  const exe = executionEventCount ?? 0;
  return (
    <div
      className={cn(
        "rounded-2xl border p-6 shadow-sm",
        isLight
          ? "border-amber-200 bg-white"
          : "border-amber-800/50 bg-amber-950/25",
      )}
      role="status"
    >
      <div className="flex gap-4">
        <div
          className={cn(
            "flex h-12 w-12 shrink-0 items-center justify-center rounded-xl",
            isLight ? "bg-amber-50 text-amber-700" : "bg-amber-900/40 text-amber-200",
          )}
        >
          <AlertTriangle className="h-6 w-6" aria-hidden />
        </div>
        <div className="min-w-0 flex-1 space-y-3">
          <div>
            <h3 className={cn("text-base font-semibold", isLight ? "text-[#0F172A]" : "text-amber-50")}>
              No simulated fills in this window
            </h3>
            <p className={cn("mt-1 text-sm leading-relaxed", isLight ? "text-[#475569]" : "text-amber-100/90")}>
              The replay completed, but the engine did not open or close any positions. Equity and trade charts stay flat
              until at least one fill exists.
            </p>
          </div>
          <p className={cn("text-xs font-semibold uppercase tracking-wide", isLight ? "text-[#64748B]" : "text-amber-200/80")}>
            Common reasons
          </p>
          <ul
            className={cn(
              "list-inside list-disc space-y-1.5 text-sm leading-relaxed",
              isLight ? "text-[#475569]" : "text-amber-100/85",
            )}
          >
            <li>
              <span className={cn("font-medium", isLight ? "text-[#0F172A]" : "text-amber-50")}>No candle data</span> for{" "}
              <span className="font-mono">{symbol}</span> in the selected range or timeframe (verify ingestion / DB).
            </li>
            <li>
              <span className={cn("font-medium", isLight ? "text-[#0F172A]" : "text-amber-50")}>Observed pipeline</span>:{" "}
              <span className="font-mono">{sig}</span> persisted signal{sig === 1 ? "" : "s"},{" "}
              <span className="font-mono">{exe}</span> OMS execution row{exe === 1 ? "" : "s"}. When both are zero, the
              strategy never emitted a tradable signal for this window (filters / regime / session gate).
            </li>
            <li>
              <span className={cn("font-medium", isLight ? "text-[#0F172A]" : "text-amber-50")}>Strategy conditions</span>{" "}
              never aligned (filters, thresholds, or regime did not produce a signal).
            </li>
            <li>
              <span className={cn("font-medium", isLight ? "text-[#0F172A]" : "text-amber-50")}>Window too narrow</span> or
              outside liquid hours so the strategy had too few bars to act.
            </li>
          </ul>
          <p className={cn("text-sm leading-relaxed", isLight ? "text-[#64748B]" : "text-amber-200/80")}>
            <span className={cn("font-medium", isLight ? "text-[#0F172A]" : "text-amber-50")}>What to try:</span> widen the
            date range,
            confirm market data exists for the symbol and timeframe, or review the strategy&apos;s published parameters and
            signal logic.
          </p>
        </div>
      </div>
    </div>
  );
}

export function BacktestRunDetailsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const { runId } = useParams<{ runId: string }>();
  const qc = useQueryClient();

  const q = useQuery({
    queryKey: ["backtest-run", runId],
    queryFn: () => getRunDetail(runId!),
    enabled: !!runId,
  });

  const resume = useMutation({
    mutationFn: () => resumeRun(runId!),
    onSuccess: (res) => {
      toast.success("Resume finished", {
        description: res.correlationId ? `Correlation ${res.correlationId}` : undefined,
      });
      qc.invalidateQueries({ queryKey: ["backtest-run", runId] });
      qc.invalidateQueries({ queryKey: ["backtest-runs"] });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  if (!runId) {
    return <div className={cn("text-sm", isLight ? "text-[#64748B]" : "text-neutral-400")}>Missing run id.</div>;
  }

  if (q.isLoading) {
    return <div className={cn("text-sm", isLight ? "text-[#64748B]" : "text-neutral-400")}>Loading run...</div>;
  }
  if (q.isError || !q.data) {
    return <div className="text-sm text-red-500">{parseAxiosMessage(q.error)}</div>;
  }

  const o = q.data.outcome;
  const equityChart =
    o.equityCurve?.map((p) => ({
      t: new Date(p.pointTime).toLocaleString("en-IN", { month: "short", day: "numeric", hour: "2-digit", timeZone: "Asia/Kolkata", hour12: false }),
      pnl: Number(p.cumulativePnl),
      dd: Number(p.drawdown),
    })) ?? [];

  const rangeLabel =
    o.rangeStart && o.rangeEnd
      ? `${new Date(o.rangeStart).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", hour12: false })} -> ${new Date(o.rangeEnd).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", hour12: false })}`
      : null;
  const hasCurvePoints = equityChart.length > 0;
  const noFills = (o.metrics?.totalTrades ?? 0) === 0;

  const canResume = o.runStatus === "RUNNING" || o.runStatus === "FAILED";

  const gridStroke = isLight ? "#e2e8f0" : "#262626";
  const axisTick = isLight ? "#64748B" : "#737373";
  const tooltipStyle = isLight
    ? { background: "#ffffff", border: "1px solid rgba(15,23,42,0.12)", borderRadius: 8 }
    : { background: "#0a0a0a", border: "1px solid #262626" };

  const cardClass = isLight
    ? "rounded-2xl border border-slate-900/[0.08] bg-white p-4 shadow-sm"
    : "rounded-2xl border border-neutral-800 bg-neutral-950/60 p-4";

  const sectionTitle = isLight ? "text-sm font-medium text-[#0F172A]" : "text-sm font-medium text-white";

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link
            to="/backtests/history"
            className={cn("text-xs", isLight ? "text-[#64748B] hover:text-[#0F172A]" : "text-neutral-500 hover:text-neutral-300")}
          >
            Back to History
          </Link>
          <h2 className={cn("mt-2 font-mono text-lg", isLight ? "text-[#0F172A]" : "text-white")}>{o.runId}</h2>
          <div className={cn("mt-1 flex flex-wrap gap-3 text-xs", isLight ? "text-[#64748B]" : "text-neutral-400")}>
            <span>
              Status{" "}
              <span className={isLight ? "font-medium text-[#0F172A]" : "text-neutral-200"}>{o.runStatus}</span>
            </span>
            <span>
              Strategy <span className={cn("font-mono", isLight ? "text-[#0F172A]" : "text-neutral-200")}>{o.strategyKey}</span>
            </span>
            <span>
              Symbol <span className={cn("font-mono", isLight ? "text-[#0F172A]" : "text-neutral-200")}>{o.symbol}</span>
            </span>
            {o.timeframe ? (
              <span>
                TF <span className={cn("font-mono", isLight ? "text-[#0F172A]" : "text-neutral-200")}>{o.timeframe}</span>
              </span>
            ) : null}
            {rangeLabel ? (
              <span className="max-w-xl">
                Range <span className={isLight ? "text-[#0F172A]" : "text-neutral-200"}>{rangeLabel}</span>
              </span>
            ) : null}
            {q.data.correlationId ? (
              <span className="font-mono text-[#64748B]">Correlation {q.data.correlationId}</span>
            ) : null}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link
            to={`/backtests/${runId}/replay`}
            className={cn(
              "rounded-xl border px-4 py-2 text-sm transition",
              isLight
                ? "border-slate-900/[0.12] text-[#0F172A] hover:bg-[#F8FAFC]"
                : "border-neutral-700 text-neutral-100 hover:bg-neutral-900",
            )}
          >
            Replay viz
          </Link>
          {canResume ? (
            <button
              type="button"
              disabled={resume.isPending}
              onClick={() => resume.mutate()}
              className={cn(
                "rounded-xl border px-4 py-2 text-sm font-medium disabled:opacity-50",
                isLight
                  ? "border-amber-300 bg-amber-50 text-amber-950 hover:bg-amber-100/90"
                  : "border-amber-700/80 bg-amber-950/40 text-amber-100 hover:bg-amber-950",
              )}
            >
              {resume.isPending ? "Resuming..." : "Resume / restart replay"}
            </button>
          ) : null}
        </div>
      </div>

      {!o.materialized ? (
        <div
          className={cn(
            "rounded-xl border px-4 py-3 text-sm",
            isLight
              ? "border-amber-200 bg-amber-50/80 text-amber-950"
              : "border-amber-800/60 bg-amber-950/30 text-amber-100",
          )}
        >
          Metrics are not fully materialized yet (interrupted run or still executing). Use resume if the run left a valid
          checkpoint, or launch a new replay.
        </div>
      ) : null}

      {o.materialized && noFills ? (
        <NoFillsEmptyState
          symbol={o.symbol}
          isLight={isLight}
          strategySignalCount={o.validation.strategySignalCount}
          executionEventCount={o.validation.executionEventCount}
        />
      ) : null}

      <div className={cn(cardClass, "p-5")}>
        <div className={sectionTitle}>Replay integrity</div>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <div className={cn("text-[11px] uppercase tracking-wide", isLight ? "text-[#64748B]" : "text-neutral-500")}>
              SHA-256 chain
            </div>
            <div className={cn("mt-1 break-all font-mono text-xs", isLight ? "text-[#475569]" : "text-neutral-300")}>
              {o.validation.replayHash}
            </div>
          </div>
          <div>
            <div className={cn("text-[11px] uppercase tracking-wide", isLight ? "text-[#64748B]" : "text-neutral-500")}>
              Deterministic flag
            </div>
            <div className={cn("mt-1 text-sm", isLight ? "text-[#0F172A]" : "text-neutral-200")}>
              {String(o.validation.deterministic)}
            </div>
          </div>
          <div>
            <div className={cn("text-[11px] uppercase tracking-wide", isLight ? "text-[#64748B]" : "text-neutral-500")}>
              Strategy signals
            </div>
            <div className={cn("mt-1 text-sm tabular-nums", isLight ? "text-[#0F172A]" : "text-neutral-200")}>
              {o.validation.strategySignalCount ?? "-"}
            </div>
          </div>
          <div>
            <div className={cn("text-[11px] uppercase tracking-wide", isLight ? "text-[#64748B]" : "text-neutral-500")}>
              OMS executions
            </div>
            <div className={cn("mt-1 text-sm tabular-nums", isLight ? "text-[#0F172A]" : "text-neutral-200")}>
              {o.validation.executionEventCount ?? "-"}
            </div>
          </div>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-3 lg:grid-cols-4">
        <Metric label="Win rate" value={fmtNum(o.metrics.winRate, 4)} isLight={isLight} />
        <Metric label="Total trades" value={String(o.metrics.totalTrades ?? 0)} isLight={isLight} />
        <Metric label="Profit factor" value={fmtNum(o.metrics.profitFactor)} isLight={isLight} />
        <Metric label="Sharpe" value={fmtNum(o.metrics.sharpeRatio)} isLight={isLight} />
        <Metric label="Max DD" value={fmtNum(o.metrics.maxDrawdown)} isLight={isLight} />
        <Metric label="Expectancy" value={fmtNum(o.metrics.expectancy)} isLight={isLight} />
        <Metric label="Total PnL" value={fmtNum(o.metrics.totalPnl)} isLight={isLight} />
        <Metric label="Avg hold (s)" value={String(o.metrics.avgHoldingTimeSeconds ?? "-")} isLight={isLight} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className={cardClass}>
          <div className={sectionTitle}>Equity curve</div>
          <div className="relative h-64">
            {!hasCurvePoints ? (
              <div
                className={cn(
                  "flex h-full flex-col items-center justify-center rounded-lg border border-dashed px-4 text-center text-sm",
                  isLight
                    ? "border-slate-900/[0.12] bg-[#F8FAFC] text-[#64748B]"
                    : "border-neutral-700 bg-neutral-900/40 text-neutral-500",
                )}
              >
                No equity points - nothing to plot until at least one simulated trade PnL exists.
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={equityChart}>
                  <CartesianGrid strokeDasharray="3 3" stroke={gridStroke} />
                  <XAxis dataKey="t" tick={{ fill: axisTick, fontSize: 10 }} />
                  <YAxis tick={{ fill: axisTick, fontSize: 10 }} />
                  <Tooltip contentStyle={tooltipStyle} />
                  <Area type="monotone" dataKey="pnl" stroke="#60a5fa" fill="#3b82f633" />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
        <div className={cardClass}>
          <div className={sectionTitle}>Drawdown</div>
          <div className="relative h-64">
            {!hasCurvePoints ? (
              <div
                className={cn(
                  "flex h-full flex-col items-center justify-center rounded-lg border border-dashed px-4 text-center text-sm",
                  isLight
                    ? "border-slate-900/[0.12] bg-[#F8FAFC] text-[#64748B]"
                    : "border-neutral-700 bg-neutral-900/40 text-neutral-500",
                )}
              >
                No drawdown series without equity steps.
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={equityChart}>
                  <CartesianGrid strokeDasharray="3 3" stroke={gridStroke} />
                  <XAxis dataKey="t" tick={{ fill: axisTick, fontSize: 10 }} />
                  <YAxis tick={{ fill: axisTick, fontSize: 10 }} />
                  <Tooltip contentStyle={tooltipStyle} />
                  <Line type="monotone" dataKey="dd" stroke="#f87171" dot={false} strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      </div>

      <div className={cardClass}>
        <div className={sectionTitle}>Trades</div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead
              className={cn(
                "border-b",
                isLight ? "border-slate-900/[0.08] text-[#64748B]" : "border-neutral-800 text-neutral-500",
              )}
            >
              <tr>
                <th className="py-2 pr-3">Side</th>
                <th className="py-2 pr-3">Qty</th>
                <th className="py-2 pr-3">Price</th>
                <th className="py-2 pr-3">PnL</th>
                <th className="py-2">Time</th>
              </tr>
            </thead>
            <tbody>
              {(o.trades ?? []).length === 0 ? (
                <tr>
                  <td colSpan={5} className={cn("py-6", isLight ? "text-[#64748B]" : "text-neutral-500")}>
                    No trades recorded for this run.
                  </td>
                </tr>
              ) : (
                o.trades.map((t) => (
                  <tr
                    key={t.id}
                    className={cn(
                      "border-b font-mono last:border-b-0",
                      isLight ? "border-slate-900/[0.08] hover:bg-[#F8FAFC]" : "border-neutral-900",
                    )}
                  >
                    <td className={cn("py-2 pr-3", isLight ? "text-[#0F172A]" : "text-neutral-200")}>{t.side}</td>
                    <td className={cn("py-2 pr-3", isLight ? "text-[#475569]" : "")}>{fmtNum(t.quantity, 6)}</td>
                    <td className={cn("py-2 pr-3", isLight ? "text-[#475569]" : "")}>{fmtNum(t.price, 4)}</td>
                    <td className={cn("py-2 pr-3", isLight ? "text-[#475569]" : "")}>{fmtNum(t.pnl, 4)}</td>
                    <td className={cn("py-2", isLight ? "text-[#64748B]" : "text-neutral-400")}>
                      {t.closedAt ? new Date(t.closedAt).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", hour12: false }) : "-"}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function Metric({ label, value, isLight }: { label: string; value: string; isLight: boolean }) {
  return (
    <div
      className={cn(
        "rounded-xl border px-4 py-3 shadow-sm",
        isLight ? "border-slate-900/[0.08] bg-[#F8FAFC]" : "border-neutral-800 bg-neutral-950/80",
      )}
    >
      <div className={cn("text-[11px] font-semibold uppercase tracking-wide", isLight ? "text-[#64748B]" : "text-neutral-500")}>
        {label}
      </div>
      <div className={cn("mt-1 font-mono text-lg", isLight ? "text-[#0F172A]" : "text-white")}>{value}</div>
    </div>
  );
}
