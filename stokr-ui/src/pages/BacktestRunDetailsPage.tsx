import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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

function fmtNum(v: string | number | undefined, digits = 4) {
  if (v === undefined || v === null) return "—";
  const n = typeof v === "number" ? v : Number(v);
  if (!Number.isFinite(n)) return String(v);
  return n.toFixed(digits);
}

export function BacktestRunDetailsPage() {
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
    return <div className="text-sm text-neutral-400">Missing run id.</div>;
  }

  if (q.isLoading) {
    return <div className="text-sm text-neutral-400">Loading run…</div>;
  }
  if (q.isError || !q.data) {
    return <div className="text-sm text-red-400">{parseAxiosMessage(q.error)}</div>;
  }

  const o = q.data.outcome;
  const equityChart =
    o.equityCurve?.map((p) => ({
      t: new Date(p.pointTime).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit" }),
      pnl: Number(p.cumulativePnl),
      dd: Number(p.drawdown),
    })) ?? [];

  const rangeLabel =
    o.rangeStart && o.rangeEnd
      ? `${new Date(o.rangeStart).toLocaleString()} → ${new Date(o.rangeEnd).toLocaleString()}`
      : null;
  const hasCurvePoints = equityChart.length > 0;
  const noFills = (o.metrics?.totalTrades ?? 0) === 0;

  const canResume = o.runStatus === "RUNNING" || o.runStatus === "FAILED";

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/backtests/history" className="text-xs text-neutral-500 hover:text-neutral-300">
            ← History
          </Link>
          <h2 className="mt-2 font-mono text-lg text-white">{o.runId}</h2>
          <div className="mt-1 flex flex-wrap gap-3 text-xs text-neutral-400">
            <span>
              Status <span className="text-neutral-200">{o.runStatus}</span>
            </span>
            <span>
              Strategy <span className="font-mono text-neutral-200">{o.strategyKey}</span>
            </span>
            <span>
              Symbol <span className="font-mono text-neutral-200">{o.symbol}</span>
            </span>
            {o.timeframe ? (
              <span>
                TF <span className="font-mono text-neutral-200">{o.timeframe}</span>
              </span>
            ) : null}
            {rangeLabel ? (
              <span className="max-w-xl">
                Range <span className="text-neutral-200">{rangeLabel}</span>
              </span>
            ) : null}
            {q.data.correlationId ? (
              <span className="font-mono text-neutral-500">Correlation {q.data.correlationId}</span>
            ) : null}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link
            to={`/backtests/${runId}/replay`}
            className="rounded-xl border border-neutral-700 px-4 py-2 text-sm text-neutral-100 hover:bg-neutral-900"
          >
            Replay viz
          </Link>
          {canResume ? (
            <button
              type="button"
              disabled={resume.isPending}
              onClick={() => resume.mutate()}
              className="rounded-xl border border-amber-700/80 bg-amber-950/40 px-4 py-2 text-sm font-medium text-amber-100 hover:bg-amber-950 disabled:opacity-50"
            >
              {resume.isPending ? "Resuming…" : "Resume / restart replay"}
            </button>
          ) : null}
        </div>
      </div>

      {!o.materialized ? (
        <div className="rounded-xl border border-amber-800/60 bg-amber-950/30 px-4 py-3 text-sm text-amber-100">
          Metrics are not fully materialized yet (interrupted run or still executing). Use resume if the run left a valid
          checkpoint, or launch a new replay.
        </div>
      ) : null}

      {o.materialized && noFills ? (
        <div className="rounded-xl border border-sky-900/50 bg-sky-950/25 px-4 py-3 text-sm text-sky-100">
          <p className="font-medium text-sky-50">No simulated fills for this run — charts stay empty.</p>
          <p className="mt-2 text-sky-200/90">
            Common causes: no candle data for <span className="font-mono">{o.symbol}</span> in the chosen range (check the
            DB / ingest ticks), or the strategy produced no signals over those bars. Try another symbol, widen the date
            range, or confirm marketdata exists for your timeframe.
          </p>
        </div>
      ) : null}

      <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
        <div className="text-sm font-medium text-white">Replay integrity</div>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <div>
            <div className="text-[11px] uppercase tracking-wide text-neutral-500">SHA-256 chain</div>
            <div className="mt-1 break-all font-mono text-xs text-neutral-300">{o.validation.replayHash}</div>
          </div>
          <div>
            <div className="text-[11px] uppercase tracking-wide text-neutral-500">Deterministic flag</div>
            <div className="mt-1 text-sm text-neutral-200">{String(o.validation.deterministic)}</div>
          </div>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-3 lg:grid-cols-4">
        <Metric label="Win rate" value={fmtNum(o.metrics.winRate, 4)} />
        <Metric label="Total trades" value={String(o.metrics.totalTrades ?? 0)} />
        <Metric label="Profit factor" value={fmtNum(o.metrics.profitFactor)} />
        <Metric label="Sharpe" value={fmtNum(o.metrics.sharpeRatio)} />
        <Metric label="Max DD" value={fmtNum(o.metrics.maxDrawdown)} />
        <Metric label="Expectancy" value={fmtNum(o.metrics.expectancy)} />
        <Metric label="Total PnL" value={fmtNum(o.metrics.totalPnl)} />
        <Metric label="Avg hold (s)" value={String(o.metrics.avgHoldingTimeSeconds ?? "—")} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-4">
          <div className="mb-3 text-sm font-medium text-white">Equity curve</div>
          <div className="relative h-64">
            {!hasCurvePoints ? (
              <div className="flex h-full flex-col items-center justify-center rounded-lg border border-dashed border-neutral-700 bg-neutral-900/40 px-4 text-center text-sm text-neutral-500">
                No equity points — nothing to plot until at least one simulated trade PnL exists.
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={equityChart}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#262626" />
                  <XAxis dataKey="t" tick={{ fill: "#737373", fontSize: 10 }} />
                  <YAxis tick={{ fill: "#737373", fontSize: 10 }} />
                  <Tooltip contentStyle={{ background: "#0a0a0a", border: "1px solid #262626" }} />
                  <Area type="monotone" dataKey="pnl" stroke="#60a5fa" fill="#3b82f633" />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-4">
          <div className="mb-3 text-sm font-medium text-white">Drawdown</div>
          <div className="relative h-64">
            {!hasCurvePoints ? (
              <div className="flex h-full flex-col items-center justify-center rounded-lg border border-dashed border-neutral-700 bg-neutral-900/40 px-4 text-center text-sm text-neutral-500">
                No drawdown series without equity steps.
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={equityChart}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#262626" />
                  <XAxis dataKey="t" tick={{ fill: "#737373", fontSize: 10 }} />
                  <YAxis tick={{ fill: "#737373", fontSize: 10 }} />
                  <Tooltip contentStyle={{ background: "#0a0a0a", border: "1px solid #262626" }} />
                  <Line type="monotone" dataKey="dd" stroke="#f87171" dot={false} strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      </div>

      <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-4">
        <div className="mb-3 text-sm font-medium text-white">Trades</div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="border-b border-neutral-800 text-neutral-500">
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
                  <td colSpan={5} className="py-6 text-neutral-500">
                    No trades recorded for this run.
                  </td>
                </tr>
              ) : (
                o.trades.map((t) => (
                  <tr key={t.id} className="border-b border-neutral-900 font-mono">
                    <td className="py-2 pr-3 text-neutral-200">{t.side}</td>
                    <td className="py-2 pr-3">{fmtNum(t.quantity, 6)}</td>
                    <td className="py-2 pr-3">{fmtNum(t.price, 4)}</td>
                    <td className="py-2 pr-3">{fmtNum(t.pnl, 4)}</td>
                    <td className="py-2 text-neutral-400">
                      {t.closedAt ? new Date(t.closedAt).toLocaleString() : "—"}
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

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-neutral-800 bg-neutral-950/80 px-4 py-3">
      <div className="text-[11px] font-semibold uppercase tracking-wide text-neutral-500">{label}</div>
      <div className="mt-1 font-mono text-lg text-white">{value}</div>
    </div>
  );
}
