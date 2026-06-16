package com.stokr.marketdata.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time in-memory order book pressure tracker.
 *
 * Receives totalBuyQuantity / totalSellQuantity from Kite quote-mode packets
 * and maintains per-symbol pressure snapshots + rolling history.
 *
 * The strategy layer reads this during evaluate() to get institutional
 * buy/sell pressure signals without any DB overhead.
 *
 * Data flow: KiteWebSocket → PlatformLiveTickEvent → PressureIngestListener → here
 *            Strategy.evaluate() → reads PressureSnapshot from here
 */
@Component
@Slf4j
public class OrderBookPressureTracker {

    /** Maximum snapshots per symbol (ring buffer). At ~1 tick/sec this is ~10 min of history. */
    private static final int MAX_SNAPSHOTS = 600;

    private final ConcurrentHashMap<String, SymbolPressure> pressureMap = new ConcurrentHashMap<>();

    /**
     * Called on every tick from the WebSocket ingest listener.
     * Stores the latest buy/sell quantities and maintains rolling history.
     */
    public void update(String symbol, long totalBuyQty, long totalSellQty, double price, Instant tickTime) {
        if (symbol == null || tickTime == null) return;
        pressureMap.computeIfAbsent(symbol, k -> new SymbolPressure())
                   .addSnapshot(totalBuyQty, totalSellQty, price, tickTime);
    }

    /**
     * Returns the current pressure state for a symbol.
     * Returns null if no data or insufficient data.
     */
    public PressureSnapshot getSnapshot(String symbol) {
        SymbolPressure sp = pressureMap.get(symbol);
        if (sp == null || sp.count < 3) return null;
        return sp.currentSnapshot();
    }

    /**
     * Returns the multi-bar pressure analysis (looks at pressure over last N candle periods).
     * Returns null if insufficient data.
     */
    public PressureAnalysis analyze(String symbol, int lookbackTicks) {
        SymbolPressure sp = pressureMap.get(symbol);
        if (sp == null || sp.count < lookbackTicks) return null;
        return sp.analyze(lookbackTicks);
    }

    /** Latest live tick time for a symbol (from in-memory OBI feed). */
    public Instant getLastUpdate(String symbol) {
        if (symbol == null) {
            return null;
        }
        SymbolPressure sp = pressureMap.get(symbol);
        if (sp == null || sp.lastUpdate == null || Instant.EPOCH.equals(sp.lastUpdate)) {
            return null;
        }
        return sp.lastUpdate;
    }

    /** Clears stale data for symbols not updated recently */
    public void evictOlderThan(Instant cutoff) {
        pressureMap.entrySet().removeIf(e -> e.getValue().lastUpdate.isBefore(cutoff));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public data records
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Current pressure state for a single symbol.
     *
     * @param buyQty          latest total pending buy quantity
     * @param sellQty         latest total pending sell quantity
     * @param imbalanceRatio  buyQty / (buyQty + sellQty), range 0.0-1.0
     *                        > 0.60 = buy pressure, < 0.40 = sell pressure
     * @param price           latest price
     * @param timestamp       when this snapshot was taken
     */
    public record PressureSnapshot(
            long buyQty, long sellQty,
            double imbalanceRatio, double price,
            Instant timestamp
    ) {
        public boolean hasBuyPressure()  { return imbalanceRatio > 0.58; }
        public boolean hasSellPressure() { return imbalanceRatio < 0.42; }
        public boolean isNeutral()       { return !hasBuyPressure() && !hasSellPressure(); }
    }

    /**
     * Multi-tick pressure analysis over a lookback window.
     *
     * @param avgImbalance       average imbalance ratio over window
     * @param imbalanceTrend     change in imbalance: positive = building buy pressure
     * @param pressureConsistency fraction of ticks where imbalance was in same direction (0-1)
     * @param volumePressure     whether volume is accelerating in direction of imbalance
     * @param priceChangesPct    price % change over the window
     * @param buyPressureTicks   how many ticks had buy pressure (ratio > 0.58)
     * @param sellPressureTicks  how many ticks had sell pressure (ratio < 0.42)
     * @param totalTicks         total ticks analyzed
     */
    public record PressureAnalysis(
            double avgImbalance,
            double imbalanceTrend,
            double pressureConsistency,
            boolean volumePressure,
            double priceChangesPct,
            int buyPressureTicks,
            int sellPressureTicks,
            int totalTicks
    ) {
        /** Strong buy pressure: consistent imbalance > 0.58 with positive price */
        public boolean isStrongBuyPressure() {
            return avgImbalance > 0.58 && pressureConsistency > 0.60 && priceChangesPct > 0;
        }
        /** Strong sell pressure: consistent imbalance < 0.42 with negative price */
        public boolean isStrongSellPressure() {
            return avgImbalance < 0.42 && pressureConsistency > 0.60 && priceChangesPct < 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal ring buffer per symbol
    // ═══════════════════════════════════════════════════════════════════════════

    private static final class SymbolPressure {
        private final long[] buyQtys = new long[MAX_SNAPSHOTS];
        private final long[] sellQtys = new long[MAX_SNAPSHOTS];
        private final double[] prices = new double[MAX_SNAPSHOTS];
        private final long[] timestamps = new long[MAX_SNAPSHOTS]; // epoch millis
        private int head = 0;  // next write position
        private int count = 0;
        private volatile Instant lastUpdate = Instant.EPOCH;

        synchronized void addSnapshot(long buyQty, long sellQty, double price, Instant time) {
            buyQtys[head]   = buyQty;
            sellQtys[head]  = sellQty;
            prices[head]    = price;
            timestamps[head] = time.toEpochMilli();
            head = (head + 1) % MAX_SNAPSHOTS;
            if (count < MAX_SNAPSHOTS) count++;
            lastUpdate = time;
        }

        synchronized PressureSnapshot currentSnapshot() {
            int latest = (head - 1 + MAX_SNAPSHOTS) % MAX_SNAPSHOTS;
            long bq = buyQtys[latest];
            long sq = sellQtys[latest];
            double total = bq + sq;
            double ratio = total > 0 ? (double) bq / total : 0.5;
            return new PressureSnapshot(bq, sq, ratio, prices[latest],
                    Instant.ofEpochMilli(timestamps[latest]));
        }

        synchronized PressureAnalysis analyze(int lookbackTicks) {
            int n = Math.min(lookbackTicks, count);
            if (n < 3) return null;

            double sumRatio = 0;
            int buyTicks = 0, sellTicks = 0;
            int dominantDirectionTicks = 0;

            // Start from oldest in window, work toward newest
            int startIdx = (head - n + MAX_SNAPSHOTS) % MAX_SNAPSHOTS;
            int endIdx = (head - 1 + MAX_SNAPSHOTS) % MAX_SNAPSHOTS;

            double firstPrice = prices[startIdx];
            double lastPrice = prices[endIdx];

            // First pass: compute average imbalance and direction counts
            double firstRatio = 0, lastRatio = 0;
            for (int i = 0; i < n; i++) {
                int idx = (startIdx + i) % MAX_SNAPSHOTS;
                long bq = buyQtys[idx];
                long sq = sellQtys[idx];
                double total = bq + sq;
                double ratio = total > 0 ? (double) bq / total : 0.5;

                sumRatio += ratio;
                if (ratio > 0.58) buyTicks++;
                if (ratio < 0.42) sellTicks++;

                if (i == 0) firstRatio = ratio;
                if (i == n - 1) lastRatio = ratio;
            }

            double avgImbalance = sumRatio / n;
            double imbalanceTrend = lastRatio - firstRatio;

            // Consistency: what fraction of ticks had the dominant pressure?
            boolean dominantBuy = avgImbalance > 0.50;
            dominantDirectionTicks = dominantBuy ? buyTicks : sellTicks;
            double consistency = (double) dominantDirectionTicks / n;

            // Price change
            double priceChangePct = firstPrice > 0 ? (lastPrice - firstPrice) / firstPrice * 100.0 : 0;

            // Volume pressure: check if last 1/3 of window has higher volume than first 1/3
            // (we don't have volume in pressure tracker, so use price momentum as proxy)
            boolean volumePressure = Math.abs(priceChangePct) > 0.05;

            return new PressureAnalysis(
                    avgImbalance, imbalanceTrend, consistency, volumePressure,
                    priceChangePct, buyTicks, sellTicks, n
            );
        }
    }
}
