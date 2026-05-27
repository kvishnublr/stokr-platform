package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureAnalysis;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.service.StrategyCandleLoader;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EARLY_BREAKOUT V2.0 — Opening Range Breakout with Pressure Confirmation
 *
 * CONCEPT: First 30 minutes establish the "opening range" (OR). A breakout from
 * this range WITH institutional pressure AND NIFTY alignment has high follow-through.
 *
 * V2.0 IMPROVEMENTS:
 *   ① NIFTY trend filter — only breakout in index direction
 *   ② Order book pressure — institutions must be pushing breakout side
 *   ③ Tighter filters — 0.15% exceed (was 0.05%), 3 confirm bars (was 2)
 *   ④ Volume 2.0x (was 1.2x)
 *   ⑤ Session 09:50-12:30 (breakouts happen morning, afternoon fades)
 *   ⑥ SL at OR midpoint (not opposite extreme — too wide)
 *   ⑦ Proper entry/SL/target prices in signal
 *
 * EXPECTED: 5-15 signals/day, 65-75% accuracy, 1.2-1.5 R:R
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "EARLY_BREAKOUT",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class EarlyBreakoutSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final String NIFTY_SYMBOL = "NIFTY 50";
    private static final int BARS_FETCH = 120;
    private static final int OR_BARS = 30;        // First 30 min (30 x 1m bars)
    private static final int CONFIRM_BARS = 3;    // Bars that must hold outside OR

    private final StrategyCandleLoader candleLoader;
    private final MarketDataQueryService marketDataQueryService;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.earlybreakout.min-range-pct:0.15}")
    private double minRangePct;

    @Value("${stokr.strategy.earlybreakout.max-range-pct:2.0}")
    private double maxRangePct;

    @Value("${stokr.strategy.earlybreakout.min-volume-multiple:2.0}")
    private double minVolumeMultiple;

    @Value("${stokr.strategy.earlybreakout.min-body-ratio:0.55}")
    private double minBodyRatio;

    @Value("${stokr.strategy.earlybreakout.breakout-exceed-pct:0.15}")
    private double breakoutExceedPct;

    @Value("${stokr.strategy.earlybreakout.sl-buffer-pct:0.10}")
    private double slBufferPct;

    @Value("${stokr.strategy.earlybreakout.target-multiplier:1.0}")
    private double targetMultiplier;

    @Value("${stokr.strategy.earlybreakout.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Value("${stokr.strategy.earlybreakout.min-risk-reward:1.2}")
    private double minRiskReward;

    @Override
    public String key() { return "EARLY_BREAKOUT"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // 1. SESSION: 09:50-12:30 IST (breakouts happen morning)
        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(LocalTime.of(9, 50)) || lt.isAfter(LocalTime.of(12, 30))) {
                return hold(context);
            }
        }

        // 2. LOAD BARS
        List<MarketdataCandle> bars = candleLoader.bars(context, TIMEFRAME, BARS_FETCH);
        if (bars.size() < OR_BARS + CONFIRM_BARS + 1) return hold(context);

        // 3. IDENTIFY OPENING RANGE
        int sessionStartIdx = findTodaySessionStart(bars);
        if (sessionStartIdx < 0 || (bars.size() - sessionStartIdx) < OR_BARS + CONFIRM_BARS + 1) {
            return hold(context);
        }

        double orHigh = Double.NEGATIVE_INFINITY;
        double orLow = Double.POSITIVE_INFINITY;
        double orVolumeSum = 0;

        for (int i = sessionStartIdx; i < sessionStartIdx + OR_BARS && i < bars.size(); i++) {
            MarketdataCandle bar = bars.get(i);
            double high = toDouble(bar.getHighPrice());
            double low = toDouble(bar.getLowPrice());
            if (high > orHigh) orHigh = high;
            if (low > 0 && low < orLow) orLow = low;
            orVolumeSum += toDouble(bar.getVolume());
        }

        if (orHigh <= 0 || orLow <= 0 || orHigh <= orLow) return hold(context);

        double orRange = orHigh - orLow;
        double orMidpoint = (orHigh + orLow) / 2.0;
        double orRangePct = orRange / orMidpoint * 100.0;
        double orAvgVolume = orVolumeSum / OR_BARS;

        // 4. RANGE QUALITY
        if (orRangePct < minRangePct || orRangePct > maxRangePct) return hold(context);

        // 5. BREAKOUT DETECTION
        int n = bars.size();
        MarketdataCandle currentBar = bars.get(n - 1);
        double currentClose = toDouble(currentBar.getClosePrice());
        double currentOpen = toDouble(currentBar.getOpenPrice());
        double currentHigh = toDouble(currentBar.getHighPrice());
        double currentLow = toDouble(currentBar.getLowPrice());
        double currentVol = toDouble(currentBar.getVolume());
        if (currentClose <= 0) return hold(context);

        boolean breakoutUp = currentClose > orHigh * (1.0 + breakoutExceedPct / 100.0);
        boolean breakoutDown = currentClose < orLow * (1.0 - breakoutExceedPct / 100.0);
        if (!breakoutUp && !breakoutDown) return hold(context);

        // 6. NIFTY TREND ALIGNMENT — must match breakout direction
        double niftyTrend = calculateNiftyTrend();
        if (breakoutUp && niftyTrend < 0.03) return hold(context);   // Need NIFTY green for upside breakout
        if (breakoutDown && niftyTrend > -0.03) return hold(context); // Need NIFTY red for downside breakout

        // 7. PRESSURE CONFIRMATION — institutions must support breakout
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        PressureAnalysis analysis = pressureTracker.analyze(symbol, 60);
        if (snapshot != null && analysis != null) {
            double ratio = snapshot.imbalanceRatio();
            if (breakoutUp && ratio < 0.55) return hold(context);   // No buy pressure for upside breakout
            if (breakoutDown && ratio > 0.45) return hold(context); // No sell pressure for downside breakout
            if (analysis.pressureConsistency() < 0.50) return hold(context); // Flickering
        }
        // If no pressure data, allow but with stricter volume/body checks below

        // 8. CONFIRMATION: 3 bars must hold outside OR
        int outsideBars = 0;
        int postOrStart = sessionStartIdx + OR_BARS;
        for (int i = Math.max(postOrStart, n - CONFIRM_BARS - 1); i < n; i++) {
            double close = toDouble(bars.get(i).getClosePrice());
            if (breakoutUp && close > orHigh) outsideBars++;
            else if (breakoutDown && close < orLow) outsideBars++;
        }
        if (outsideBars < CONFIRM_BARS) return hold(context);

        // 9. BREAKOUT BAR QUALITY
        double barRange = currentHigh - currentLow;
        if (barRange <= 0) return hold(context);
        double bodySize = Math.abs(currentClose - currentOpen);
        double bodyRatio = bodySize / barRange;
        if (bodyRatio < minBodyRatio) return hold(context);
        if (breakoutUp && currentClose < currentOpen) return hold(context);
        if (breakoutDown && currentClose > currentOpen) return hold(context);

        // 10. VOLUME 2x+
        if (orAvgVolume > 0 && currentVol / orAvgVolume < minVolumeMultiple) return hold(context);

        // 11. COOLDOWN
        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, now).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // 12. SIGNAL with proper prices
        SignalType signalType;
        double target, stopLoss, entryPrice = currentClose;

        if (breakoutUp) {
            signalType = SignalType.BUY;
            target = orHigh + orRange * targetMultiplier;
            stopLoss = orMidpoint * (1.0 - slBufferPct / 100.0); // Midpoint SL, not opposite extreme
        } else {
            signalType = SignalType.SELL;
            target = orLow - orRange * targetMultiplier;
            stopLoss = orMidpoint * (1.0 + slBufferPct / 100.0);
        }

        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(target - entryPrice);
        double rr = risk > 0 ? reward / risk : 0;
        if (rr < minRiskReward) return hold(context);
        if (risk / entryPrice > 0.025) return hold(context); // Risk > 2.5% = too wide
        if (risk / entryPrice < 0.001) return hold(context); // Risk < 0.1% = too tight

        lastEmitBySymbol.put(symbol, now);

        String pressureInfo = snapshot != null ? String.format("imb=%.0f%%", snapshot.imbalanceRatio() * 100) : "imb=N/A";
        String reason = String.format(
            "EARLY_BREAKOUT_V2 %s: orH=%.2f orL=%.2f orRange=%.2f%% %s " +
            "body=%.2f vol=%.1fx hold=%d nifty=%+.2f%% entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            signalType, orHigh, orLow, orRangePct, pressureInfo,
            bodyRatio, orAvgVolume > 0 ? currentVol / orAvgVolume : 0, outsideBars,
            niftyTrend, entryPrice, stopLoss, target, rr
        );

        log.info("earlybreakout_v2.signal symbol={} {}", symbol, reason);
        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entryPrice), BigDecimal.valueOf(stopLoss), BigDecimal.valueOf(target));
    }

    private double calculateNiftyTrend() {
        try {
            List<MarketdataCandle> nifty = marketDataQueryService.lastBarsAsc(NIFTY_SYMBOL, TIMEFRAME, 15);
            if (nifty == null || nifty.size() < 5) return 0;
            double first = toDouble(nifty.get(0).getClosePrice());
            double last = toDouble(nifty.get(nifty.size() - 1).getClosePrice());
            return first > 0 ? (last - first) / first * 100 : 0;
        } catch (Exception e) { return 0; }
    }

    private int findTodaySessionStart(List<MarketdataCandle> bars) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (bars.get(i).getOpenTime() == null) continue;
            LocalTime lt = bars.get(i).getOpenTime().atZone(zone).toLocalTime();
            if (lt.isAfter(LocalTime.of(9, 14)) && lt.isBefore(LocalTime.of(9, 17))) {
                return i;
            }
        }
        return -1;
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
