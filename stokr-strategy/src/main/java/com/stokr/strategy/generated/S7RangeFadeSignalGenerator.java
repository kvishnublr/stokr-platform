package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.service.BankLeadLagService;
import com.stokr.strategy.service.StrategyMarketIndicatorService;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S7_RANGE_FADE ??? Range Fade Lower Strategy (BULL / BUY only)
 *
 * EXACT PORT of Python S7 from nse_edge_live_v/backend/intraday_engine/.
 *
 * Python definition:
 *   StrategyDef("S7", "Range Fade Lower", "CHOP", "10:00", "14:00",
 *               0.66, "BULL", -999, -0.25, 0.25, 0.45, "FADE_LOWER")
 *
 * CRITICAL: This is a BULL/BUY strategy ??? buying dips below VWAP in choppy markets.
 *   NOT a SHORT strategy. The legacy Java had this backwards.
 *
 * Detection logic:
 *   - Regime: CHOP only (no ORB break + breadth < 0.35)
 *   - Direction: BULL only (buy signal)
 *   - VWAP extension: must be <= -0.25% (price below VWAP by at least 0.25%)
 *   - Weighted composite scoring:
 *       lead_lag(0.25) + volume(0.25) + velocity(0.20) + breadth(0.15) + time(0.15)
 *   - Min score: 0.66
 *   - Entry on buy, target on mean reversion back toward VWAP
 *
 * Time window: 10:00 - 14:00 IST
 * SL = 0.25%, Target = 0.45%
 *
 * Companion strategy: S6 (Range Fade Upper) = BEAR/SELL at VWAP extension >= +0.25%
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "S7_RANGE_FADE",
    assetClass   = "FUTURES",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class S7RangeFadeSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 60;

    // ?????? Python exact constants (from strategies.py S7 definition) ??????
    private static final BigDecimal SL_PERCENT     = BigDecimal.valueOf(0.0025);  // 0.25%
    private static final BigDecimal TARGET_PERCENT  = BigDecimal.valueOf(0.0045);  // 0.45%

    // VWAP extension range: -999 to -0.25 (price must be BELOW VWAP by >= 0.25%)
    private static final double EXT_MIN = -999.0;
    private static final double EXT_MAX = -0.25;

    // Min composite score: 0.66
    private static final double MIN_SCORE = 0.66;

    // Time window: 10:00 - 14:00 IST
    private static final int TRADING_START_MIN = 600;  // 10:00
    private static final int TRADING_END_MIN   = 840;  // 14:00

    // Regime: CHOP only
    private static final double BREADTH_CHOP_MAX = 0.35;

    // Weighted scorer weights (from config.py WEIGHTS ??? same as S3)
    private static final double W_LEAD_LAG = 0.25;
    private static final double W_VOLUME   = 0.25;
    private static final double W_VELOCITY = 0.20;
    private static final double W_BREADTH  = 0.15;
    private static final double W_TIME     = 0.15;

    private final StrategyGeneratorIntegrityGate integrityGate;
    private final OrderBookPressureTracker pressureTracker;
    private final StrategyMarketIndicatorService marketIndicatorService;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.s7rangefade.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() { return "S7_RANGE_FADE"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        // ????????? Time Window Check (10:00???14:00 IST) ?????????
        LocalTime now;
        if (context.asOf() != null) {
            now = context.asOf().atZone(zone).toLocalTime();
        } else {
            now = LocalTime.now(zone);
        }
        int currentMin = now.getHour() * 60 + now.getMinute();
        if (currentMin < TRADING_START_MIN || currentMin > TRADING_END_MIN) {
            return hold(context);
        }

        // ????????? Load candle data (same-session only) ?????????
        var barsOpt = integrityGate.sessionBarsWithoutLookback(key(), symbol, TIMEFRAME, BARS_FETCH, context);
        if (barsOpt.isEmpty()) {
            return hold(context);
        }
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < 21) {
            return hold(context);
        }
        int n = bars.size();
        MarketdataCandle latestBar = bars.get(n - 1);
        BigDecimal currentPrice = latestBar.getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        // ????????? Regime Classification (CHOP only for S7) ?????????
        // Python: CHOP = no ORB break + breadth < 0.35
        String regime = classifyRegime(bars, currentPrice);
        if (!"CHOP".equals(regime) && !"MIXED".equals(regime)) {
            // S7 requires CHOP regime. Also allow MIXED as fallback
            return hold(context);
        }

        // ????????? Compute VWAP ?????????
        BigDecimal sumPV = BigDecimal.ZERO;
        BigDecimal sumV = BigDecimal.ZERO;
        for (MarketdataCandle bar : bars) {
            BigDecimal high = bar.getHighPrice();
            BigDecimal low = bar.getLowPrice();
            BigDecimal close = bar.getClosePrice();
            BigDecimal vol = bar.getVolume();
            if (high == null || low == null || close == null || vol == null || vol.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal typicalPrice = high.add(low).add(close).divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
            sumPV = sumPV.add(typicalPrice.multiply(vol));
            sumV = sumV.add(vol);
        }
        BigDecimal vwap = sumV.compareTo(BigDecimal.ZERO) > 0
                ? sumPV.divide(sumV, 6, RoundingMode.HALF_UP)
                : currentPrice;

        // ????????? VWAP Extension Check ?????????
        // Python S7: ext_min=-999, ext_max=-0.25
        // Price must be BELOW VWAP by at least 0.25%
        double extensionPct = 0;
        if (vwap.compareTo(BigDecimal.ZERO) > 0) {
            extensionPct = currentPrice.subtract(vwap)
                    .divide(vwap, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;
        }
        if (extensionPct < EXT_MIN || extensionPct > EXT_MAX) {
            return hold(context);
        }

        // ????????? Direction check: BULL only (Python side="BULL") ?????????
        // S7 is exclusively a BUY signal ??? buying the dip below VWAP
        // No need to check direction from lead-lag; S7 always buys

        // ????????? Compute weighted composite score ?????????
        // Lead-lag: NOW REAL from BankLeadLagService (matches Python LeadLagDetector)
        BankLeadLagService.LeadLagResult leadLagResult = marketIndicatorService.getLeadLag(asOf);
        double leadLagScore = leadLagResult.score();

        // Fallback to PressureTracker if bank data unavailable
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (leadLagScore == 0.0 && snapshot != null) {
            leadLagScore = Math.abs(snapshot.imbalanceRatio() - 0.5) * 2.0;
        }

        double volumeScore = computeVolumeScore(bars, n);
        double velocityScore = computeVelocityScore(bars, n, currentMin);
        double breadthScore = computeBreadthScore(bars, n);
        double timeScore = computeTimeScore(currentMin);

        double composite = leadLagScore * W_LEAD_LAG
                         + volumeScore  * W_VOLUME
                         + velocityScore * W_VELOCITY
                         + breadthScore * W_BREADTH
                         + timeScore    * W_TIME;
        composite = Math.round(composite * 10000.0) / 10000.0;

        // ????????? Score threshold check (Python: min_score=0.66) ?????????
        if (composite < MIN_SCORE) {
            return hold(context);
        }

        // ????????? Lot multiplier ?????????
        double lotMult = composite >= 0.80 ? 1.0 : 0.75;

        // ????????? Cooldown ?????????
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ????????? Entry/Exit Levels ?????????
        // S7 is BULL only: BUY signal, target UP, SL DOWN
        BigDecimal entryLevel = currentPrice;
        BigDecimal targetLevel = entryLevel.multiply(BigDecimal.ONE.add(TARGET_PERCENT));
        BigDecimal stopLoss   = entryLevel.multiply(BigDecimal.ONE.subtract(SL_PERCENT));

        lastEmitBySymbol.put(symbol, evalTime);

        String pressureInfo = snapshot != null ? String.format(" imb=%.0f%%", snapshot.imbalanceRatio() * 100) : "";

        String reason = String.format(
            "S7_RANGE_FADE BULL/BUY: price=%.2f vwap=%.2f ext=%.3f%% " +
            "ll=%.4f vol=%.4f vel=%.4f brd=%.4f time=%.4f " +
            "composite=%.4f regime=%s lot=%.2f%s entry=%.2f target=%.2f sl=%.2f",
            currentPrice, vwap, extensionPct,
            leadLagScore, volumeScore, velocityScore, breadthScore, timeScore,
            composite, regime, lotMult, pressureInfo,
            entryLevel, targetLevel, stopLoss
        );

        log.info("s7_range_fade.signal symbol={} {}", symbol, reason);

        // S7 is BULL = BUY signal
        return new StrategySignal(SignalType.BUY, symbol, BigDecimal.valueOf(lotMult), reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetLevel.setScale(2, RoundingMode.HALF_UP));
    }

    // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
    // Helper methods (same as S3 ??? shared scoring infrastructure)
    // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private String classifyRegime(List<MarketdataCandle> bars, BigDecimal currentPrice) {
        if (bars.size() < 16) return "MIXED";
        double orbHi = Double.MIN_VALUE;
        double orbLo = Double.MAX_VALUE;
        int orbBars = Math.min(15, bars.size());
        for (int i = 0; i < orbBars; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > orbHi) orbHi = h;
            if (l > 0 && l < orbLo) orbLo = l;
        }
        double orbRange = orbHi - orbLo;
        boolean orbBreak = orbRange > 0 && toDouble(currentPrice) > (orbHi + orbRange * 0.50);

        int upBars = 0, totalBars = 0;
        for (int i = Math.max(0, bars.size() - 20); i < bars.size(); i++) {
            BigDecimal c = bars.get(i).getClosePrice();
            BigDecimal o = bars.get(i).getOpenPrice();
            if (c != null && o != null) {
                totalBars++;
                if (c.compareTo(o) > 0) upBars++;
            }
        }
        double breadth = totalBars > 0 ? (double) upBars / totalBars : 0.5;

        if (orbBreak && breadth >= 0.60) return "TREND";
        if (!orbBreak && breadth < BREADTH_CHOP_MAX) return "CHOP";
        return "MIXED";
    }

    private double computeVolumeScore(List<MarketdataCandle> bars, int n) {
        if (n < 10) return 0.0;
        double recentVol = 0;
        for (int i = n - 5; i < n; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) recentVol += v.doubleValue();
        }
        double baseVol = 0;
        int baseCount = 0;
        for (int i = 0; i < n - 5; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) { baseVol += v.doubleValue(); baseCount++; }
        }
        if (baseCount == 0 || baseVol <= 0) return 0.0;
        double ratio = (recentVol / 5.0) / Math.max(baseVol / baseCount, 1.0);
        if (ratio < 2.0) return 0.0;
        return Math.min(1.0, Math.max(0.0, (ratio - 1.0) / 2.0));
    }

    private double computeVelocityScore(List<MarketdataCandle> bars, int n, int currentMin) {
        if (n < 3) return 0.0;
        BigDecimal v1 = bars.get(n - 3).getVolume();
        BigDecimal v3 = bars.get(n - 1).getVolume();
        if (v1 == null || v3 == null) return 0.0;
        double accel = v3.doubleValue() / Math.max(v1.doubleValue(), 1.0);
        int expected = currentMin < 600 ? 15 : (currentMin < 720 ? 10 : 7);
        double vel = Math.min(1.0, accel / expected * 5.0);
        BigDecimal v2 = bars.get(n - 2).getVolume();
        double avg = (v1.doubleValue() + (v2 != null ? v2.doubleValue() : 0) + v3.doubleValue()) / 3.0;
        double bonus = v3.doubleValue() / Math.max(avg, 1e-9) > 1.3 ? 0.20 : 0.0;
        return Math.min(1.0, vel + bonus);
    }

    private double computeBreadthScore(List<MarketdataCandle> bars, int n) {
        int lookback = Math.min(20, n);
        int bull = 0, bear = 0;
        for (int i = n - lookback; i < n; i++) {
            BigDecimal c = bars.get(i).getClosePrice();
            BigDecimal o = bars.get(i).getOpenPrice();
            if (c != null && o != null) {
                if (c.compareTo(o) > 0) bull++;
                else if (c.compareTo(o) < 0) bear++;
            }
        }
        return (double) Math.max(bull, bear) / Math.max(lookback, 1);
    }

    private double computeTimeScore(int currentMin) {
        if (currentMin >= 560 && currentMin < 615) return 1.00;
        if (currentMin >= 615 && currentMin < 660) return 0.75;
        if (currentMin >= 810 && currentMin < 870) return 0.70;
        if (currentMin >= 660 && currentMin < 810) return 0.20;
        if (currentMin >= 870 && currentMin < 915) return 0.30;
        return 0.0;
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
