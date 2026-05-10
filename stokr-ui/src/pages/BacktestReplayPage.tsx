import { useQuery } from "@tanstack/react-query";
import { createChart, ColorType, type ISeriesApi, type CandlestickData, type Time } from "lightweight-charts";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client";
import { fetchRunJournal, getRunDetail } from "../api/backtest";

type CandleDto = {
  openTime: string;
  openPrice: string;
  highPrice: string;
  lowPrice: string;
  closePrice: string;
};

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
      const res = await api.get(`/api/marketdata/candles/range?${params.toString()}`);
      return (res.data?.data ?? []) as CandleDto[];
    },
    enabled: !!symbol && !!start && !!end,
  });

  const journalQ = useQuery({
    queryKey: ["backtest-journal", runId],
    queryFn: () => fetchRunJournal(runId!),
    enabled: !!runId,
  });

  const candleData: CandlestickData[] = useMemo(() => {
    return (candlesQ.data ?? []).map((c) => ({
      time: toUnixTime(c.openTime),
      open: Number(c.openPrice),
      high: Number(c.highPrice),
      low: Number(c.lowPrice),
      close: Number(c.closePrice),
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
