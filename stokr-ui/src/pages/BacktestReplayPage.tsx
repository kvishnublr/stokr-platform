import { useQuery } from "@tanstack/react-query";
import { createChart, ColorType, type ISeriesApi, type CandlestickData, type Time } from "lightweight-charts";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client";
import { fetchRunJournal, getRunDetail } from "../api/backtest";

type CandleDto = {
  openTime?: string;
  open_time?: string;
  openPrice?: string | number;
  open_price?: string | number;
  highPrice?: string | number;
  high_price?: string | number;
  lowPrice?: string | number;
  low_price?: string | number;
  closePrice?: string | number;
  close_price?: string | number;
};

function candleOpenTimeIso(c: CandleDto): string {
  const v = c.openTime ?? c.open_time;
  return typeof v === "string" ? v : "";
}

function num(v: string | number | undefined | null): number {
  if (v == null) return 0;
  return typeof v === "number" ? v : Number(v);
}

function toUnixTime(iso: string): Time {
  return Math.floor(new Date(iso).getTime() / 1000) as Time;
}

export function BacktestReplayPage() {
  const { runId } = useParams<{ runId: string }>();
  const chartRef = useRef<HTMLDivElement>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const chartObjRef = useRef<ReturnType<typeof createChart> | null>(null);

  const detailQ = useQuery({
    queryKey: ["backtest-run", runId],
    queryFn: () => getRunDetail(runId!),
    enabled: !!runId,
  });

  const outcome = detailQ.data?.outcome;
  const symbol = outcome?.symbol ?? "";
  const tf = outcome?.timeframe ?? "1m";
  const start = outcome?.rangeStart;
  const end = outcome?.rangeEnd;

  const candlesQ = useQuery({
    queryKey: ["replay-candles", symbol, tf, start, end],
    queryFn: async () => {
      const params = new URLSearchParams({
        symbol,
        timeframe: tf,
        start: start!,
        end: end!,
      });
      const res = await api.get(`/api/trader/terminal/replay/candles-range?${params.toString()}`);
      return (res.data?.data ?? []) as CandleDto[];
    },
    enabled: !!symbol && !!start && !!end,
  });

  const replayExplain = useMemo(() => {
    const o = outcome;
    if (!o?.validation) return null;
    const bars = candlesQ.data?.length ?? 0;
    const sig = o.validation.strategySignalCount ?? 0;
    const ex = o.validation.executionEventCount ?? 0;
    const trades = o.metrics?.totalTrades ?? 0;
    let diagnosis = "COMPLETED";
    const reasons: string[] = [];
    if (bars === 0) {
      diagnosis = "NO_DATA";
      reasons.push("No candles returned for this window — verify ingestion or synthetic seeding for the replay symbol.");
    } else if (trades > 0 || ex > 0) {
      diagnosis = "COMPLETED";
    } else if (sig > 0) {
      diagnosis = "EXECUTION_BLOCKED";
      reasons.push("Signals exist in the journal/store but executions are empty — inspect OMS bridge, execution mode, and risk gates.");
    } else {
      diagnosis = "NO_SIGNALS";
      reasons.push("Bars advanced but no persisted signals — strategy filters may be too strict, timeframe legs missing (e.g. 5m), or regime mismatch.");
    }
    return { diagnosis, bars, sig, ex, trades, reasons, loop: o.loopTelemetry };
  }, [outcome, candlesQ.data?.length]);

  const journalQ = useQuery({
    queryKey: ["backtest-journal", runId],
    queryFn: () => fetchRunJournal(runId!),
    enabled: !!runId,
  });

  const candleData: CandlestickData[] = useMemo(() => {
    return (candlesQ.data ?? []).map((c) => ({
      time: toUnixTime(candleOpenTimeIso(c as CandleDto)),
      open: num((c as CandleDto).openPrice ?? (c as CandleDto).open_price),
      high: num((c as CandleDto).highPrice ?? (c as CandleDto).high_price),
      low: num((c as CandleDto).lowPrice ?? (c as CandleDto).low_price),
      close: num((c as CandleDto).closePrice ?? (c as CandleDto).close_price),
    }));
  }, [candlesQ.data]);

  const [cursor, setCursor] = useState(0);

  useEffect(() => {
    if (!chartRef.current || candleData.length === 0) return;
    const chart = createChart(chartRef.current, {
      layout: { background: { type: ColorType.Solid, color: "#0a0a0a" }, textColor: "#a3a3a3" },
      grid: { vertLines: { color: "#262626" }, horzLines: { color: "#262626" } },
      width: chartRef.current.clientWidth,
      height: 420,
      timeScale: { borderColor: "#404040" },
      rightPriceScale: { borderColor: "#404040" },
    });
    const series = chart.addCandlestickSeries({
      upColor: "#22c55e",
      downColor: "#ef4444",
      borderVisible: false,
      wickUpColor: "#22c55e",
      wickDownColor: "#ef4444",
    });
    series.setData(candleData);
    chart.timeScale().fitContent();
    chartObjRef.current = chart;
    seriesRef.current = series;

    const ro = new ResizeObserver(() => {
      if (chartRef.current && chartObjRef.current) {
        chartObjRef.current.applyOptions({ width: chartRef.current.clientWidth });
      }
    });
    ro.observe(chartRef.current);

    return () => {
      ro.disconnect();
      chart.remove();
      chartObjRef.current = null;
      seriesRef.current = null;
    };
  }, [candleData]);

  useEffect(() => {
    const s = seriesRef.current;
    const chart = chartObjRef.current;
    if (!s || !chart || candleData.length === 0) return;
    const t = candleData[Math.min(cursor, candleData.length - 1)]?.time;
    if (t !== undefined) {
      chart.timeScale().scrollToPosition(Math.max(0, cursor - 30), false);
    }
  }, [cursor, candleData]);

  const markers = useMemo(() => {
    const entries = journalQ.data?.entries ?? [];
    const signalTimes = new Set<number>();
    for (const e of entries) {
      if (e.eventType !== "BACKTEST_SIGNAL_GENERATED") continue;
      try {
        const payload = JSON.parse(e.payloadJson) as { barOpenTime?: string };
        if (payload.barOpenTime) {
          signalTimes.add(Math.floor(new Date(payload.barOpenTime).getTime() / 1000));
        }
      } catch {
        /* ignore */
      }
    }
    return [...signalTimes].map((sec) => ({
      time: sec as Time,
      position: "belowBar" as const,
      color: "#60a5fa",
      shape: "arrowUp" as const,
      text: "signal",
    }));
  }, [journalQ.data]);

  useEffect(() => {
    const s = seriesRef.current;
    if (!s || markers.length === 0) return;
    s.setMarkers(markers);
  }, [markers, candleData.length]);

  if (!runId) return null;
  if (detailQ.isLoading) return <div className="text-sm text-neutral-400">Loading run…</div>;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <Link to={`/backtests/${runId}`} className="text-xs text-neutral-500 hover:text-neutral-300">
            ← Run metrics
          </Link>
          <h2 className="mt-2 text-xl font-semibold text-white">Replay visualization</h2>
          <p className="mt-1 font-mono text-xs text-neutral-400">
            {symbol} {tf} {detailQ.data?.correlationId ? `· corr ${detailQ.data.correlationId}` : ""}
          </p>
        </div>
      </div>

      {replayExplain && !candlesQ.isLoading ? (
        <div className="rounded-xl border border-neutral-700 bg-neutral-900/80 p-4">
          <div className="text-xs font-semibold uppercase tracking-wide text-neutral-300">Replay explainability</div>
          <div className="mt-2 font-mono text-sm text-white">{replayExplain.diagnosis}</div>
          <dl className="mt-3 grid gap-2 text-xs text-neutral-400 sm:grid-cols-2">
            <div>
              <dt className="text-neutral-500">Candles loaded (chart)</dt>
              <dd className="font-mono text-neutral-200">{replayExplain.bars}</dd>
            </div>
            <div>
              <dt className="text-neutral-500">Signals (persisted)</dt>
              <dd className="font-mono text-neutral-200">{replayExplain.sig}</dd>
            </div>
            <div>
              <dt className="text-neutral-500">OMS executions</dt>
              <dd className="font-mono text-neutral-200">{replayExplain.ex}</dd>
            </div>
            <div>
              <dt className="text-neutral-500">Closed trades (metrics)</dt>
              <dd className="font-mono text-neutral-200">{replayExplain.trades}</dd>
            </div>
          </dl>
          {replayExplain.loop ? (
            <div className="mt-3 font-mono text-[11px] text-neutral-500">
              Loop: processed {replayExplain.loop.candlesProcessed ?? "—"} / expected {replayExplain.loop.candlesExpected ?? "—"} · emitted{" "}
              {replayExplain.loop.signalsEmitted ?? "—"}
            </div>
          ) : null}
          <ul className="mt-3 list-disc space-y-1 pl-4 text-xs text-neutral-400">
            {replayExplain.reasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-4">
        <div ref={chartRef} className="h-[420px] w-full" />
        {candlesQ.isLoading ? <div className="mt-2 text-xs text-neutral-500">Loading candles…</div> : null}
        {candlesQ.data?.length === 0 ? (
          <div className="mt-2 text-xs text-amber-300">No candles in range — ingest market data for this symbol/tf.</div>
        ) : null}
      </div>

      <div className="rounded-xl border border-neutral-800 bg-neutral-950/60 p-4">
        <div className="mb-2 text-sm font-medium text-white">Playback scrubber</div>
        <input
          type="range"
          min={0}
          max={Math.max(0, candleData.length - 1)}
          value={Math.min(cursor, Math.max(0, candleData.length - 1))}
          onChange={(e) => setCursor(Number(e.target.value))}
          className="w-full accent-blue-500"
        />
        <div className="mt-2 font-mono text-xs text-neutral-500">
          Bar {Math.min(cursor + 1, Math.max(candleData.length, 1))} / {candleData.length}
        </div>
      </div>

      <div className="rounded-xl border border-neutral-800 bg-neutral-950/60 p-4">
        <div className="text-sm font-medium text-white">Journal tail</div>
        <div className="mt-3 max-h-56 overflow-y-auto font-mono text-[11px] text-neutral-400">
          {(journalQ.data?.entries ?? []).slice(-24).map((e) => (
            <div key={e.sequenceNum} className="border-b border-neutral-900 py-1">
              <span className="text-neutral-500">{e.eventType}</span> #{e.sequenceNum}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
