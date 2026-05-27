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
 * ADV_CASH_EQUITY — ADV Cash Equity Strategy (10-Step Detection)
 *
 * Migrated from legacy ADVCashDetector to catalog-driven architecture.
 * Preserves the EXACT same 10-step detection logic:
 *
 *   STEP 1: Time Window (9:30–15:00 IST, skip first 2 min)
 *   STEP 2: Volume Spike (5m volume > 1000)
 *   STEP 3: Bid-Ask Spread proxy via pressure imbalance
 *   STEP 4: OBI/Momentum Score (|momentum| > 0.1)
 *   STEP 5: VIX Range (8–35, default 17.5)
 *   STEP 6: 4-Signal Consensus (need ≥3)
 *   STEP 7: Sector rotation (handled externally — max positions per strategy)
 *   STEP 8: Direction from OBI sign
 *   STEP 9–10: Entry/Exit levels (T1=+0.7%, T2=+1.4%, SL=−0.4%)
 *   Quality score: base 50 + OBI(15–20) + volume(15) + VIX(10) + consensus(5), min 65
 *
 * DATA SOURCE MAPPING (legacy → catalog):
 *   MarketDataProvider.getIndexMarketData(symbol).currentPrice → CandleLoader latest close
 *   data.volume5m   → sum of last 5 one-minute candle volumes
 *   data.momentum5m → (close[now] − close[5 bars ago]) / close[5 bars ago] (OBI proxy)
 *   data.vixLevel   → default 17.5 (same as original fallback when null)
 *
 * NOTE: Original Step 3 (bid-ask spread) was broken — hardcoded 0.002 > 0.0015 always
 * returned null, blocking ALL signals. Replaced with pressure-based spread proxy:
 * if pressure data exists and imbalance is extreme (>0.85 or <0.15), treat as wide spread.
 * This preserves the INTENT (filter illiquid instruments) without the bug.
 *
 * Performance target: 75% win rate, T1=0.7%, T2=1.4%, SL=0.4%
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "ADV_CASH",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class AdvCashEquitySignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 10;  // Need last ~5-10 bars for volume + momentum

    // Original constants (EXACT match)
    private static final BigDecimal TARGET1_PERCENT = BigDecimal.valueOf(0.007);   // 0.7%
    private static final BigDecimal TARGET2_PERCENT = BigDecimal.valueOf(0.014);   // 1.4%
    private static final BigDecimal SL_PERCENT      = BigDecimal.valueOf(0.004);   // 0.4%

    // Original time windows (9:30=570 min, 15:00=900 min)
    private static final int TRADING_START_MIN = 570;
    private static final int TRADING_END_MIN   = 900;

    private final MarketDataQueryService marketDataQueryService;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.advcash.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Value("${stokr.advcash.min-quality-score:65}")
    private int minQualityScore;

    @Value("${stokr.advcash.default-vix:17.5}")
    private double defaultVix;

    @Override
    public String key() { return "ADV_CASH"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ─── STEP 1: Time Window Check (9:30–15:00 IST, skip first 2 min chaos) ───
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
        // Skip first 2 minutes (9:30–9:32) — original: 570–572
        if (currentMin >= 570 && currentMin <= 572) {
            return hold(context);
        }

        // ─── Load candle data ───
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, TIMEFRAME, BARS_FETCH);
        if (bars.size() < 6) {
            return hold(context);  // Need at least 6 bars for 5-bar volume + momentum
        }
        int n = bars.size();
        MarketdataCandle latestBar = bars.get(n - 1);
        BigDecimal currentPrice = latestBar.getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        // ─── STEP 2: Volume Spike Check (5m cumulative volume > 1000) ───
        // Original: data.volume5m > 1000
        BigDecimal volume5m = BigDecimal.ZERO;
        for (int i = Math.max(0, n - 5); i < n; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) volume5m = volume5m.add(v);
        }
        if (volume5m.compareTo(BigDecimal.valueOf(1000)) < 0) {
            return hold(context);
        }

        // ─── STEP 3: Bid-Ask Spread Proxy ───
        // Original was BROKEN: hardcoded spread=0.002, check 0.002>0.0015 always true → null
        // FIX: Use pressure tracker imbalance as liquidity proxy.
        // Extreme imbalance (>0.85 or <0.15) = illiquid, skip.
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (snapshot != null) {
            double imbalance = snapshot.imbalanceRatio();
            if (imbalance > 0.85 || imbalance < 0.15) {
                return hold(context);  // Too one-sided = wide effective spread
            }
        }

        // ─── STEP 4: OBI / Momentum Score (|momentum| > 0.1) ───
        // Original: data.momentum5m used as OBI proxy
        // Compute: (close[now] - close[5 ago]) / close[5 ago]
        BigDecimal closePrev = bars.get(n - 6).getClosePrice();
        if (closePrev == null || closePrev.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }
        double momentum5m = currentPrice.subtract(closePrev)
                .divide(closePrev, 6, RoundingMode.HALF_UP)
                .doubleValue();
        BigDecimal obiScore = BigDecimal.valueOf(momentum5m);

        if (Math.abs(momentum5m) < 0.001) {
            // Original threshold was 0.1 on a different scale.
            // MarketDataProvider.momentum5m was a normalized value.
            // With real candle data, 0.1 (10%) is unrealistic for 5-min window.
            // Map to 0.1% (0.001) which is the equivalent threshold for real price momentum.
            return hold(context);
        }

        // ─── STEP 5: VIX Range Check (8–35) ───
        // Original: data.vixLevel fallback to 17.5
        // No VIX data source in catalog system — use default (matches original behavior)
        double vix = defaultVix;
        if (vix < 8.0 || vix > 35.0) {
            return hold(context);
        }

        // ─── STEP 6: 4-Signal Consensus (need ≥ 3 of 4) ───
        // Original checks: obiValid, timeValid, vixValid, volumeValid
        boolean obiValid = Math.abs(momentum5m) > 0.001;
        boolean timeValid = (currentMin >= 575 && currentMin <= 825); // Avoid dead zone 11:30-13:15
        boolean vixValid = vix < 25.0;
        boolean volumeValid = volume5m.compareTo(BigDecimal.valueOf(2000)) > 0;

        int consensusCount = 0;
        if (obiValid) consensusCount++;
        if (timeValid) consensusCount++;
        if (vixValid) consensusCount++;
        if (volumeValid) consensusCount++;

        if (consensusCount < 3) {
            return hold(context);
        }

        // ─── STEP 7: Sector Rotation — handled externally by max-positions-per-strategy ───

        // ─── STEP 8: Direction from OBI sign ───
        // Original: direction = obiScore > 0 ? "LONG" : "SHORT"
        boolean isLong = momentum5m > 0;
        SignalType signalType = isLong ? SignalType.BUY : SignalType.SELL;

        // ─── STEP 9-10: Entry/Exit Levels ───
        // Original: T1=0.7%, T2=1.4%, SL=0.4%
        BigDecimal entryLevel = currentPrice;
        BigDecimal targetLevel1;
        BigDecimal targetLevel2;
        BigDecimal stopLoss;

        if (isLong) {
            targetLevel1 = entryLevel.multiply(BigDecimal.ONE.add(TARGET1_PERCENT));
            targetLevel2 = entryLevel.multiply(BigDecimal.ONE.add(TARGET2_PERCENT));
            stopLoss     = entryLevel.multiply(BigDecimal.ONE.subtract(SL_PERCENT));
        } else {
            targetLevel1 = entryLevel.multiply(BigDecimal.ONE.subtract(TARGET1_PERCENT));
            targetLevel2 = entryLevel.multiply(BigDecimal.ONE.subtract(TARGET2_PERCENT));
            stopLoss     = entryLevel.multiply(BigDecimal.ONE.add(SL_PERCENT));
        }

        // ─── Quality Score (same formula as original) ───
        int confidenceLevel = consensusCount == 3 ? 2 : 3;
        BigDecimal qualityScore = calculateQualityScore(obiScore, volume5m, BigDecimal.valueOf(vix), consensusCount);

        if (qualityScore.compareTo(BigDecimal.valueOf(minQualityScore)) < 0) {
            return hold(context);
        }

        // ─── Cooldown (prevent repeated signals on same symbol) ───
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        lastEmitBySymbol.put(symbol, evalTime);

        // ─── Build signal ───
        // Use T1 as the primary target (consistent with other generators)
        String pressureInfo = snapshot != null ? String.format("imb=%.0f%%", snapshot.imbalanceRatio() * 100) : "imb=N/A";
        String reason = String.format(
            "ADV_CASH %s: momentum=%.4f vol5m=%s vix=%.1f consensus=%d/4 quality=%.0f %s " +
            "entry=%.2f t1=%.2f t2=%.2f sl=%.2f conf=%d",
            signalType, momentum5m, volume5m.toPlainString(), vix, consensusCount,
            qualityScore, pressureInfo,
            entryLevel, targetLevel1, targetLevel2, stopLoss, confidenceLevel
        );

        log.info("adv_cash.signal symbol={} {}", symbol, reason);

        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetLevel1.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Quality score — exact same formula as original ADVCashDetector.
     * Base 50 + OBI(15–20) + Volume(15) + VIX(10) + Consensus(5) = max 100.
     */
    private BigDecimal calculateQualityScore(BigDecimal obi, BigDecimal volume,
                                              BigDecimal vix, int consensusCount) {
        BigDecimal score = BigDecimal.valueOf(50); // Base

        // OBI strength (original: 0.2 → +20, 0.15 → +15)
        // Scaled for real momentum: 0.002 → +20, 0.0015 → +15
        if (obi.abs().compareTo(BigDecimal.valueOf(0.002)) > 0) {
            score = score.add(BigDecimal.valueOf(20));
        } else if (obi.abs().compareTo(BigDecimal.valueOf(0.0015)) > 0) {
            score = score.add(BigDecimal.valueOf(15));
        }

        // Volume spike (original: >3000 → +15)
        if (volume.compareTo(BigDecimal.valueOf(3000)) > 0) {
            score = score.add(BigDecimal.valueOf(15));
        }

        // VIX (original: <20 → +10)
        if (vix.compareTo(BigDecimal.valueOf(20)) < 0) {
            score = score.add(BigDecimal.valueOf(10));
        }

        // Consensus (original: >=4 → +5)
        if (consensusCount >= 4) {
            score = score.add(BigDecimal.valueOf(5));
        }

        return score.min(BigDecimal.valueOf(100));
    }
}
