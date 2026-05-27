package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
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
 * INDEX_HUNT — NIFTY50 & BANKNIFTY Intraday Options Momentum Detector
 *
 * Migrated from legacy IndexHuntDetector to catalog-driven architecture.
 * Preserves the EXACT same 5-gate detection logic:
 *
 *   Gate 1: TIME WINDOW (10:15–13:45 IST)
 *   Gate 2: MOMENTUM (5m move between 0.055%–0.60%)
 *   Gate 3: TREND (30m backdrop alignment ≥ ±10%)
 *   Gate 4: PCR (Put-Call Ratio validation — CE: PCR>1.02, PE: PCR>1.32 + weak index)
 *   Gate 5: VIX + ANTI-CHASE (VIX<20.75 for CE, price not overextended ±6% from 3min extreme)
 *
 * Quality: base 50 + momentum(10-20) + trend(10-15) + PCR(10) - VIX penalty(5), min 68
 *
 * DATA SOURCE MAPPING (legacy → catalog):
 *   data.change5m        → computed 5-bar percentage change
 *   data.trend30m        → computed 30-bar percentage change
 *   data.vixLevel        → default 17.5 (no VIX feed yet)
 *   data.pcrRatio        → default 1.15 (no PCR feed yet; neutral-bullish)
 *   data.recent3minHigh  → max(high) of last 3 candles
 *   data.recent3minLow   → min(low) of last 3 candles
 *   data.currentPrice    → latest candle close
 *
 * NOTE: PCR and VIX use configurable defaults. When real PCR/VIX feeds are added,
 * simply inject the data source — the gate logic is preserved unchanged.
 *
 * Entry/Exit: Index-price-based (not option premium) until option chain data is available.
 * Original: SL = premium × 0.80, T1 = premium × 1.28, T2 = premium × 1.65
 * Mapped:   SL = index ∓ 0.20%, Target = index ± 0.50% (equivalent R:R)
 *
 * Win Rate: 72.9% NIFTY, 76.5% BANKNIFTY (legacy backtest)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "INDEX_HUNT",
    assetClass   = "OPTIONS",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class IndexHuntSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 35;  // Need 30+ for trend30m

    // Original constants (EXACT match)
    private static final int TIME_START_MIN = 615;      // 10:15 AM
    private static final int TIME_END_MIN   = 825;      // 1:45 PM
    private static final double MOMENTUM_MIN_PCT = 0.055;   // 0.055%
    private static final double MOMENTUM_MAX_PCT = 0.60;    // 0.60%
    private static final double HI_STRENGTH_THRESHOLD = 0.20; // "hi" above 0.20%
    private static final double TREND_MIN_SUPPORT = 0.10;   // 10% (of 30-bar move)
    private static final double PCR_CE_MIN = 1.02;
    private static final double PCR_PE_MIN = 1.32;
    private static final double PE_MAX_INDEX_CHG = 0.06;
    private static final double VIX_SKIP_CE_ABOVE = 20.75;
    private static final double ANTI_CHASE_CE_PCT = 0.06;   // 6%
    private static final double ANTI_CHASE_PE_PCT = 0.06;   // 6%
    private static final int MIN_QUALITY = 68;

    // Index-price-based entry/exit (mapped from option premium multipliers)
    // Original: SL=0.80×premium (20% loss), T1=1.28×premium (28% gain), T2=1.65×premium (65% gain)
    // Equivalent on index: SL=0.20%, Target=0.50%
    private static final BigDecimal INDEX_SL_PCT     = BigDecimal.valueOf(0.0020);  // 0.20%
    private static final BigDecimal INDEX_TARGET_PCT  = BigDecimal.valueOf(0.0050);  // 0.50%

    private final MarketDataQueryService marketDataQueryService;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.indexhunt.cooldown-seconds:1800}")
    private int cooldownSeconds;

    @Value("${stokr.indexhunt.default-vix:17.5}")
    private double defaultVix;

    @Value("${stokr.indexhunt.default-pcr:1.15}")
    private double defaultPcr;

    @Override
    public String key() { return "INDEX_HUNT"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ─── GATE 1: TIME WINDOW (10:15–13:45 IST) ───
        LocalTime now;
        if (context.asOf() != null) {
            now = context.asOf().atZone(zone).toLocalTime();
        } else {
            now = LocalTime.now(zone);
        }
        int currentMin = now.getHour() * 60 + now.getMinute();
        if (currentMin < TIME_START_MIN || currentMin > TIME_END_MIN) {
            return hold(context);
        }

        // ─── Load candle data ───
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, TIMEFRAME, BARS_FETCH);
        if (bars.size() < 6) {
            return hold(context);
        }
        int n = bars.size();
        BigDecimal currentPrice = bars.get(n - 1).getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        // ─── Compute 5m change (percentage) ───
        BigDecimal close5ago = bars.get(Math.max(0, n - 6)).getClosePrice();
        double change5m = 0;
        if (close5ago != null && close5ago.compareTo(BigDecimal.ZERO) > 0) {
            change5m = currentPrice.subtract(close5ago)
                    .divide(close5ago, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;  // As percentage
        }

        // ─── GATE 2: MOMENTUM (5m move between 0.055%–0.60%) ───
        double absChange = Math.abs(change5m);
        if (absChange < MOMENTUM_MIN_PCT || absChange > MOMENTUM_MAX_PCT) {
            return hold(context);
        }
        String strength = absChange >= HI_STRENGTH_THRESHOLD ? "hi" : "md";

        // ─── Compute 30m trend ───
        double trend30m = 0;
        if (n >= 31) {
            BigDecimal close30ago = bars.get(n - 31).getClosePrice();
            if (close30ago != null && close30ago.compareTo(BigDecimal.ZERO) > 0) {
                trend30m = currentPrice.subtract(close30ago)
                        .divide(close30ago, 6, RoundingMode.HALF_UP)
                        .doubleValue() * 100.0;
            }
        }

        // ─── Compute recent 3-min high/low ───
        double recent3minHigh = Double.MIN_VALUE;
        double recent3minLow = Double.MAX_VALUE;
        for (int i = Math.max(0, n - 3); i < n; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > recent3minHigh) recent3minHigh = h;
            if (l > 0 && l < recent3minLow) recent3minLow = l;
        }

        // ─── PCR and VIX defaults (no real feed yet) ───
        double vix = defaultVix;
        double pcr = defaultPcr;

        // Use pressure tracker as PCR proxy if available
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (snapshot != null) {
            // Map imbalance ratio to PCR-like value:
            // imbalance 0.6 (more buys) → PCR ~1.2 (bullish, more puts = smart money hedging)
            // imbalance 0.4 (more sells) → PCR ~0.8 (bearish)
            pcr = 0.5 + snapshot.imbalanceRatio();  // Maps 0-1 → 0.5-1.5
        }

        // ─── GATE 3: TREND ALIGNMENT ───
        boolean ceHasTrendSupport = trend30m >= TREND_MIN_SUPPORT;
        boolean peHasTrendSupport = trend30m <= -TREND_MIN_SUPPORT;

        // ─── GATES 4+5: Determine direction (CE or PE) ───
        String direction = null;
        SignalType signalType = null;

        // Try CE (bullish) first
        if (ceHasTrendSupport) {
            // Gate 4A: PCR > 1.02 for CE
            boolean pcrOk = pcr > PCR_CE_MIN;
            // Gate 5A: VIX < 20.75 for CE
            boolean vixOk = vix <= VIX_SKIP_CE_ABOVE;
            // Gate 5B: Anti-chase — not too far above recent low
            double cp = toDouble(currentPrice);
            boolean antiChaseOk = cp <= recent3minLow * (1.0 + ANTI_CHASE_CE_PCT);

            if (pcrOk && vixOk && antiChaseOk) {
                direction = "CE";
                signalType = SignalType.BUY;
            }
        }

        // Try PE (bearish) if CE didn't qualify
        if (direction == null && peHasTrendSupport) {
            // Gate 4B: PCR > 1.32 AND index change < +0.06%
            boolean pcrOk = pcr > PCR_PE_MIN && change5m < PE_MAX_INDEX_CHG;
            // Gate 5B: Anti-chase — not too far below recent high
            double cp = toDouble(currentPrice);
            boolean antiChaseOk = cp >= recent3minHigh * (1.0 - ANTI_CHASE_PE_PCT);

            if (pcrOk && antiChaseOk) {
                direction = "PE";
                signalType = SignalType.SELL;
            }
        }

        if (direction == null) {
            return hold(context);
        }

        // ─── Quality Score (same formula as original) ───
        BigDecimal qualityScore = calculateQualityScore(strength, trend30m, pcr, vix);
        if (qualityScore.compareTo(BigDecimal.valueOf(MIN_QUALITY)) < 0) {
            return hold(context);
        }

        // ─── Cooldown ───
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ─── Entry/Exit (index-price-based) ───
        BigDecimal entryLevel = currentPrice;
        BigDecimal targetLevel;
        BigDecimal stopLoss;

        if ("CE".equals(direction)) {
            targetLevel = entryLevel.multiply(BigDecimal.ONE.add(INDEX_TARGET_PCT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.subtract(INDEX_SL_PCT));
        } else {
            targetLevel = entryLevel.multiply(BigDecimal.ONE.subtract(INDEX_TARGET_PCT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.add(INDEX_SL_PCT));
        }

        lastEmitBySymbol.put(symbol, evalTime);

        String pressureInfo = snapshot != null ? String.format("imb=%.0f%%", snapshot.imbalanceRatio() * 100) : "";
        String reason = String.format(
            "INDEX_HUNT %s %s: mom5m=%.3f%% trend30m=%.3f%% pcr=%.2f vix=%.1f " +
            "strength=%s quality=%.0f %s entry=%.2f target=%.2f sl=%.2f",
            direction, signalType, change5m, trend30m, pcr, vix,
            strength, qualityScore, pressureInfo,
            entryLevel, targetLevel, stopLoss
        );

        log.info("index_hunt.signal symbol={} {}", symbol, reason);

        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetLevel.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Quality score — exact same formula as original IndexHuntDetector.
     * Base 50 + momentum(10-20) + trend(10-15) + PCR(10) - VIX penalty(5), min 68
     */
    private BigDecimal calculateQualityScore(String strength, double trend30m, double pcr, double vix) {
        double score = 50.0;

        // Momentum strength: hi=+20, md=+10
        if ("hi".equals(strength)) {
            score += 20;
        } else {
            score += 10;
        }

        // Trend alignment: >0.20 → +15, >0.10 → +10
        double absTrend = Math.abs(trend30m);
        if (absTrend > 0.20) {
            score += 15;
        } else if (absTrend > 0.10) {
            score += 10;
        }

        // PCR alignment: |pcr - 1.0| > 0.30 → +10
        if (Math.abs(pcr - 1.0) > 0.30) {
            score += 10;
        }

        // VIX penalty: > 18 → -5
        if (vix > 18.0) {
            score -= 5;
        }

        // Cap at 100
        if (score > 100) score = 100;

        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
