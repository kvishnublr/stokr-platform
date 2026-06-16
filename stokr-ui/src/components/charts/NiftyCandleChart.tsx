import { useEffect, useMemo, useRef } from "react";
import { createChart, ColorType, type CandlestickData, type HistogramData, type IChartApi, type Time } from "lightweight-charts";
import { cn } from "../../lib/utils";

type CandlePoint = { time: number; open: number; high: number; low: number; close: number };
type VolumePoint = { time: number; value: number; up?: boolean };

type NiftyCandleChartProps = {
  variant?: "light" | "dark";
  height?: number;
  className?: string;
  candles?: CandlePoint[];
  volumes?: VolumePoint[];
};

export function NiftyCandleChart({ variant = "light", height = 340, className, candles = [], volumes = [] }: NiftyCandleChartProps) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);

  const mappedCandles = useMemo<CandlestickData[]>(
    () =>
      candles.map((c) => ({
        time: c.time as Time,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
      })),
    [candles],
  );
  const mappedVolumes = useMemo<HistogramData[]>(
    () =>
      volumes.map((v) => ({
        time: v.time as Time,
        value: v.value,
        color: v.up === false ? "rgba(239, 68, 68, 0.45)" : "rgba(16, 185, 129, 0.45)",
      })),
    [volumes],
  );

  useEffect(() => {
    const el = wrapRef.current;
    if (!el || mappedCandles.length === 0) return;

    const isLight = variant === "light";
    const bg = isLight ? "#ffffff" : "#0a0a0a";
    const text = isLight ? "#334155" : "#a3a3a3";
    const gridMajor = isLight ? "#e7e5e4" : "#262626";

    const chart = createChart(el, {
      layout: { background: { type: ColorType.Solid, color: bg }, textColor: text },
      grid: { vertLines: { color: gridMajor }, horzLines: { color: gridMajor } },
      width: el.clientWidth,
      height,
      crosshair: { mode: 1 },
      timeScale: { borderColor: isLight ? "#d6d3d1" : "#404040" },
      rightPriceScale: { borderColor: isLight ? "#d6d3d1" : "#404040" },
    });

    const series = chart.addCandlestickSeries({
      upColor: "#10b981",
      downColor: "#f43f5e",
      borderVisible: false,
      wickUpColor: "#059669",
      wickDownColor: "#e11d48",
    });
    series.setData(mappedCandles);

    series.priceScale().applyOptions({
      scaleMargins: { top: 0.04, bottom: 0.26 },
    });

    const vol = chart.addHistogramSeries({
      priceFormat: { type: "volume" },
      priceScaleId: "",
      color: "#3b82f6",
    });
    vol.setData(mappedVolumes);
    chart.priceScale("").applyOptions({
      scaleMargins: { top: 0.74, bottom: 0 },
    });

    chart.timeScale().fitContent();
    chartRef.current = chart;

    const ro = new ResizeObserver(() => {
      if (!wrapRef.current || !chartRef.current) return;
      chartRef.current.applyOptions({ width: wrapRef.current.clientWidth });
    });
    ro.observe(el);

    return () => {
      ro.disconnect();
      chart.remove();
      chartRef.current = null;
    };
  }, [mappedCandles, mappedVolumes, height, variant]);

  return (
    <div ref={wrapRef} className={cn("w-full min-h-[200px]", className)} style={{ height }}>
      {mappedCandles.length === 0 ? (
        <div className="flex h-full items-center justify-center text-xs font-medium text-neutral-500">
          No market candle data available
        </div>
      ) : null}
    </div>
  );
}
