package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.LookbackWindow;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NIFTY_CATCHUP — Index Catch-Up / Relative Lag Breakout
 *
 * EDGE: When Nifty 50 moves strongly but a constituent stock lags, two
 * mechanical forces push the stock to catch up within 15–20 min:
 *   (1) Index arb bots buy lagging cash stocks when futures premium > fair value
 *   (2) ₹3.5L Cr passive AUM rebalances NAV daily by buying under-performing stocks
 *
 * CONDITIONS (ALL required):
 *   1. Nifty 15-min return >= +0.35%
 *   2. Stock 15-min return < 40% of Nifty's move (lagging)
 *   3. Stock 15-min return >= -0.10% (not genuinely bearish)
 *   4. OBI imbalance ratio > 0.55 and rising (buy pressure confirmed)
 *   5. Volume >= 1.2x session average (confirms institutional activity)
 *   6. Time: 09:45 – 14:15 IST (post-open noise, pre-close crush)
 *
 * EXIT:
 *   Target = entry × (1 + nifty_15m_return + targetBuffer)
 *   SL     = entry × (1 - slPct)
 *   Min R:R check applied before signal emitted
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "NIFTY_CATCHUP",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class NiftyCatchUpSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME   = "1m";
    private static final String NIFTY_SYM   = "NIFTY 50";
    private static final int    NIFTY_BARS  = 20;
    private static final int    STOCK_BARS  = 20;
    private static final int    BARS_FETCH  = 30;

    private final StrategyGeneratorIntegrityGate integrityGate;
    private final OrderBookPressureTracker        pressureTracker;

    private final ConcurrentHashMap<String, Instant>        lastEmitBySymbol = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Double>>  obiHistory       = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.niftycatchup.nifty-min-move-pct:0.25}")
    private double niftyMinMovePct;

    @Value("${stokr.strategy.niftycatchup.stock-lag-ratio:0.40}")
    private double stockLagRatio;

    @Value("${stokr.strategy.niftycatchup.stock-min-return-pct:-0.10}")
    private double stockMinReturnPct;

    @Value("${stokr.strategy.niftycatchup.obi-min:0.52}")
    private double obiMin;

    @Value("${stokr.strategy.niftycatchup.min-volume-multiple:1.2}")
    private double minVolumeMultiple;

    @Value("${stokr.strategy.niftycatchup.sl-pct:0.0025}")
    private double slPct;

    @Value("${stokr.strategy.niftycatchup.target-buffer-pct:0.001}")
    private double targetBufferPct;

    @Value("${stokr.strategy.niftycatchup.min-risk-reward:1.3}")
    private double minRiskReward;

    @Value("${stokr.strategy.niftycatchup.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() { return "NIFTY_CATCHUP"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf  = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) return hold(context);

        // 1. SESSION: 09:45 – 14:15 IST
        LocalTime lt = asOf.atZone(zone).toLocalTime();
        if (lt.isBefore(LocalTime.of(9, 45)) || lt.isAfter(LocalTime.of(14, 15))) {
            return hold(context);
        }

        // 2. NIFTY 15-min return
        double niftyReturn = calcReturn(NIFTY_SYM, 15, context);
        if (niftyReturn < niftyMinMovePct) return hold(context);

        // 3. STOCK 15-min return
        double stockReturn = calcReturn(symbol, 15, context);
        if (stockReturn < stockMinReturnPct) return hold(context);
        if (stockReturn >= stockLagRatio * niftyReturn) return hold(context); // not lagging

        // 4. LOAD STOCK BARS (needed for both volume gate and order-flow proxy)
        var barsOpt = integrityGate.sessionBars(
                key(), symbol, TIMEFRAME, BARS_FETCH, 5, LookbackWindow.FIVE_MINUTE, context);
        if (barsOpt.isEmpty() || barsOpt.get().size() < 8) return hold(context);
        List<MarketdataCandle> bars = barsOpt.get();
        int n = bars.size();

        // 5. OBI check — prefer live book, fall back to OHLCV proxy (works in backtest).
        //    Previously obi defaulted to 0.5 with a 0.55 threshold, so the gate could
        //    NEVER pass without a live book → zero signals in every backtest.
        PressureSnapshot snap = pressureTracker.getSnapshot(symbol);
        double obi = resolvePressure(snap != null ? snap.imbalanceRatio() : null, bars, 5);
        Deque<Double> hist = obiHistory.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        hist.addLast(obi);
        while (hist.size() > 5) hist.removeFirst();

        if (obi < obiMin) return hold(context);
        // "Rising" guard only meaningful with a continuous live feed; the proxy is
        // computed per-bar so a sharp-decline veto would falsely fire in replay.
        if (snap != null && hist.size() >= 2) {
            double[] arr = hist.stream().mapToDouble(Double::doubleValue).toArray();
            double prev = arr[arr.length - 2];
            if (obi < prev * 0.95) return hold(context); // OBI declining sharply — skip
        }

        // 6. VOLUME check — stock must show activity >= 1.2x avg
        double totalVol = 0;
        for (MarketdataCandle b : bars) totalVol += toDouble(b.getVolume());
        double avgVol   = totalVol / n;
        double curVol   = toDouble(bars.get(n - 1).getVolume());
        if (avgVol > 0 && curVol / avgVol < minVolumeMultiple) return hold(context);

        // 6. COOLDOWN (guard backtest time-travel: ignore stale cooldown from a
        //    prior run when replay jumps backwards — see ADV_CASH for details)
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null) {
            long sinceLast = Duration.between(lastEmit, asOf).getSeconds();
            if (sinceLast >= 0 && sinceLast < cooldownSeconds) {
                return hold(context);
            }
        }

        // 7. PRICES
        double entry  = toDouble(bars.get(n - 1).getClosePrice());
        double target = entry * (1.0 + niftyReturn / 100.0 + targetBufferPct);
        double sl     = entry * (1.0 - slPct);
        double risk   = entry - sl;
        double reward = target - entry;
        if (risk <= 0 || reward / risk < minRiskReward) return hold(context);
        // Cap SL at 0.5% to avoid wide stops
        if (risk / entry > 0.005) return hold(context);

        lastEmitBySymbol.put(symbol, asOf);

        String reason = String.format(
            "NIFTY_CATCHUP: nifty15m=+%.3f%% stock15m=+%.3f%% lag=%.0f%% obi=%.2f vol=%.1fx " +
            "entry=%.2f sl=%.2f target=%.2f rr=%.2f",
            niftyReturn, stockReturn, stockReturn / niftyReturn * 100.0, obi, avgVol > 0 ? curVol / avgVol : 0,
            entry, sl, target, reward / risk
        );
        log.info("nifty_catchup.signal symbol={} {}", symbol, reason);

        return new StrategySignal(SignalType.BUY, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP));
    }

    /** Returns the N-bar return (%) for the given symbol using 1m candles. */
    private double calcReturn(String sym, int lookbackBars, StrategyContext context) {
        try {
            var opt = integrityGate.sessionBars(
                    key(), sym, TIMEFRAME, lookbackBars + 5, 4, LookbackWindow.FIVE_MINUTE, context);
            if (opt.isEmpty() || opt.get().size() < lookbackBars) return 0;
            List<MarketdataCandle> bars = opt.get();
            int sz   = bars.size();
            double last  = toDouble(bars.get(sz - 1).getClosePrice());
            double first = toDouble(bars.get(Math.max(0, sz - lookbackBars)).getClosePrice());
            return first > 0 ? (last - first) / first * 100.0 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
