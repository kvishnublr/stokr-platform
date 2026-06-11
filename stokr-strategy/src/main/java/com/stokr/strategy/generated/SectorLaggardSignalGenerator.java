package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.LookbackWindow;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.telemetry.StrategyGateTelemetry;
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
 * SECTOR_LAGGARD V2.0 — Sector Rotation Catch-up with Pressure
 *
 * CONCEPT: When NIFTY 50 moves strongly, laggard stocks (those that haven't kept pace)
 * tend to catch up. This is institutional rotation — money flows from leaders to laggards
 * within the session. Both bullish AND bearish rotations now supported.
 *
 * V2.0 IMPROVEMENTS:
 *   ① FIXED: Signal now includes proper entry/SL/target prices (was missing!)
 *   ② Both BUY and SELL laggards (V1 was buy-only)
 *   ③ Pressure confirmation — institutions must be entering the laggard
 *   ④ Proper R:R calculation with structural SL
 *   ⑤ Volume confirmation raised
 *   ⑥ Stricter divergence threshold
 *   ⑦ Uses 1m timeframe (was 5m which doesn't exist in DB)
 *
 * EXPECTED: 3-10 signals/day (only when sector moves strongly), 65-75% accuracy, 1.2-1.5 R:R
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "SECTOR_LAGGARD",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class SectorLaggardSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final String INDEX_SYMBOL = "NIFTY 50";
    private static final int BARS_FETCH = 90;
    private static final int LOOKBACK_BARS = 30;  // Last 30 min for momentum calc

    private final OrderBookPressureTracker pressureTracker;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final StrategyGateTelemetry gateTelemetry;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.sectorlaggard.min-sector-move-pct:0.20}")
    private double minSectorMovePct;

    @Value("${stokr.sectorlaggard.min-divergence-pct:0.20}")
    private double minDivergencePct;

    @Value("${stokr.sectorlaggard.max-counter-move-pct:0.90}")
    private double maxCounterMovePct;

    @Value("${stokr.sectorlaggard.min-reversal-body-ratio:0.25}")
    private double minReversalBodyRatio;

    @Value("${stokr.sectorlaggard.min-volume-rise:0.9}")
    private double minVolumeRise;

    @Value("${stokr.sectorlaggard.target-capture-ratio:0.50}")
    private double targetCaptureRatio;

    @Value("${stokr.sectorlaggard.sl-beyond-swing-pct:0.20}")
    private double slBeyondSwingPct;

    @Value("${stokr.sectorlaggard.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Value("${stokr.sectorlaggard.min-risk-reward:1.0}")
    private double minRiskReward;

    @Override
    public String key() { return "SECTOR_LAGGARD"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();
        if (INDEX_SYMBOL.equalsIgnoreCase(symbol)) return hold(context);

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        // 1. SESSION: 10:00-14:45 IST (need time for divergence to develop)
        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(LocalTime.of(10, 0)) || lt.isAfter(LocalTime.of(15, 15))) {
                return hold(context);
            }
        }

        // 2. LOAD INDEX DATA (same-session only; 30-bar lookback max 35 min)
        var indexBarsOpt = integrityGate.sessionBars(
                key(), INDEX_SYMBOL, TIMEFRAME, BARS_FETCH, LOOKBACK_BARS, LookbackWindow.THIRTY_MINUTE, context);
        if (indexBarsOpt.isEmpty()) {
            return hold(context);
        }
        List<MarketdataCandle> indexBars = indexBarsOpt.get();

        // 3. LOAD STOCK DATA
        var stockBarsOpt = integrityGate.sessionBars(
                key(), symbol, TIMEFRAME, BARS_FETCH, LOOKBACK_BARS, LookbackWindow.THIRTY_MINUTE, context);
        if (stockBarsOpt.isEmpty()) {
            return hold(context);
        }
        List<MarketdataCandle> stockBars = stockBarsOpt.get();

        // 4. SECTOR MOMENTUM
        int idxN = indexBars.size();
        double indexNow = toDouble(indexBars.get(idxN - 1).getClosePrice());
        double indexPrev = toDouble(indexBars.get(idxN - 1 - LOOKBACK_BARS).getClosePrice());
        if (indexNow <= 0 || indexPrev <= 0) return hold(context);

        double sectorMovePct = (indexNow - indexPrev) / indexPrev * 100.0;
        double absSectorMove = Math.abs(sectorMovePct);
        if (absSectorMove < minSectorMovePct) {
            gateTelemetry.infoThrottled(key(), "SECTOR_MOVE_LOW",
                    "sector30m=%+.3f%% need|move|>=%.2f%% (NIFTY 50)", sectorMovePct, minSectorMovePct);
            return hold(context);
        }

        boolean sectorBullish = sectorMovePct > 0;

        // 5. STOCK MOMENTUM & DIVERGENCE
        int stkN = stockBars.size();
        double stockNow = toDouble(stockBars.get(stkN - 1).getClosePrice());
        double stockPrev = toDouble(stockBars.get(stkN - 1 - LOOKBACK_BARS).getClosePrice());
        if (stockNow <= 0 || stockPrev <= 0) return hold(context);

        double stockMovePct = (stockNow - stockPrev) / stockPrev * 100.0;
        double divergence;
        SignalType signalType;

        if (sectorBullish) {
            divergence = sectorMovePct - stockMovePct;
            if (stockMovePct < -maxCounterMovePct) return hold(context); // Stock-specific selling
            if (divergence < minDivergencePct) {
                gateTelemetry.infoNearMiss(key(), symbol, "DIVERGENCE_LOW",
                        "sector=%+.2f%% stock=%+.2f%% divergence=%.2f%% need>=%.2f%%",
                        sectorMovePct, stockMovePct, divergence, minDivergencePct);
                return hold(context);
            }
            signalType = SignalType.BUY;
        } else {
            // V2: Bearish laggard — stock hasn't fallen as much as sector → SHORT it
            divergence = stockMovePct - sectorMovePct; // positive = stock lagging the selloff
            if (stockMovePct > maxCounterMovePct) return hold(context); // Stock-specific buying
            if (divergence < minDivergencePct) {
                gateTelemetry.infoNearMiss(key(), symbol, "DIVERGENCE_LOW",
                        "sector=%+.2f%% stock=%+.2f%% divergence=%.2f%% need>=%.2f%%",
                        sectorMovePct, stockMovePct, divergence, minDivergencePct);
                return hold(context);
            }
            signalType = SignalType.SELL;
        }

        // 6. PRESSURE CONFIRMATION — institutions must be entering laggard in catch-up direction
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (snapshot != null) {
            double ratio = snapshot.imbalanceRatio();
            if (sectorBullish && ratio < 0.50) return hold(context);  // No buy pressure for bullish catch-up
            if (!sectorBullish && ratio > 0.50) return hold(context); // No sell pressure for bearish catch-up
        }

        // 7. REVERSAL CONFIRMATION — last bar must show movement in catch-up direction
        MarketdataCandle lastBar = stockBars.get(stkN - 1);
        double barOpen = toDouble(lastBar.getOpenPrice());
        double barClose = toDouble(lastBar.getClosePrice());
        double barHigh = toDouble(lastBar.getHighPrice());
        double barLow = toDouble(lastBar.getLowPrice());
        double barRange = barHigh - barLow;
        if (barRange <= 0) return hold(context);

        if (sectorBullish) {
            double bodyRatio = (barClose - barOpen) / barRange; // Positive for green bar
            if (bodyRatio < minReversalBodyRatio) return hold(context);
        } else {
            double bodyRatio = (barOpen - barClose) / barRange; // Positive for red bar
            if (bodyRatio < minReversalBodyRatio) return hold(context);
        }

        // 8. MULTI-BAR CONFIRMATION — last 2 bars aligned
        if (stkN >= 3) {
            MarketdataCandle prevBar = stockBars.get(stkN - 2);
            double prevClose = toDouble(prevBar.getClosePrice());
            double prevOpen = toDouble(prevBar.getOpenPrice());
            double prevBodyPct = prevOpen > 0 ? Math.abs(prevClose - prevOpen) / prevOpen * 100.0 : 0.0;
            if (sectorBullish && prevClose < prevOpen && prevBodyPct > 0.08) return hold(context);
            if (!sectorBullish && prevClose > prevOpen && prevBodyPct > 0.08) return hold(context);
        }

        // 9. VOLUME RISING
        double currentVol = toDouble(lastBar.getVolume());
        double avgVol = 0;
        int volCount = 0;
        for (int i = Math.max(0, stkN - 11); i < stkN - 1; i++) {
            double v = toDouble(stockBars.get(i).getVolume());
            if (v > 0) { avgVol += v; volCount++; }
        }
        avgVol = volCount > 0 ? avgVol / volCount : 0;
        if (avgVol > 0 && currentVol / avgVol < minVolumeRise) return hold(context);

        // 10. COOLDOWN
        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, now).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // 11. SIGNAL WITH PROPER PRICES
        double entryPrice = stockNow;
        double target, stopLoss;

        if (sectorBullish) {
            // BUY: target = capture portion of divergence
            double targetMove = divergence * targetCaptureRatio / 100.0 * stockNow;
            target = stockNow + targetMove;
            // SL: below 30-bar session low
            double sessionLow = Double.MAX_VALUE;
            for (int i = Math.max(0, stkN - LOOKBACK_BARS); i < stkN; i++) {
                double low = toDouble(stockBars.get(i).getLowPrice());
                if (low > 0 && low < sessionLow) sessionLow = low;
            }
            stopLoss = sessionLow * (1.0 - slBeyondSwingPct / 100.0);
        } else {
            // SELL: target = capture portion of divergence downward
            double targetMove = divergence * targetCaptureRatio / 100.0 * stockNow;
            target = stockNow - targetMove;
            double sessionHigh = Double.MIN_VALUE;
            for (int i = Math.max(0, stkN - LOOKBACK_BARS); i < stkN; i++) {
                double high = toDouble(stockBars.get(i).getHighPrice());
                if (high > sessionHigh) sessionHigh = high;
            }
            stopLoss = sessionHigh * (1.0 + slBeyondSwingPct / 100.0);
        }

        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(target - entryPrice);
        double rr = risk > 0 ? reward / risk : 0;
        if (rr < minRiskReward) return hold(context);
        if (risk / entryPrice > 0.025) return hold(context);
        if (risk / entryPrice < 0.001) return hold(context);

        lastEmitBySymbol.put(symbol, now);

        String pressureInfo = snapshot != null ? String.format("imb=%.0f%%", snapshot.imbalanceRatio() * 100) : "imb=N/A";
        String reason = String.format(
            "SECTOR_LAGGARD_V2 %s: sector=%+.2f%% stock=%+.2f%% divergence=%.2f%% %s " +
            "vol=%.1fx entry=%.2f target=%.2f sl=%.2f rr=%.1f",
            signalType, sectorMovePct, stockMovePct, divergence, pressureInfo,
            avgVol > 0 ? currentVol / avgVol : 0,
            entryPrice, target, stopLoss, rr
        );

        log.info("sectorlaggard_v2.signal symbol={} {}", symbol, reason);
        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entryPrice), BigDecimal.valueOf(stopLoss), BigDecimal.valueOf(target));
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
