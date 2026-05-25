package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NSE 1-minute spike detection strategy.
 *
 * Detects sudden momentum bursts on NSE equities using a 6-component composite score:
 *
 *   1. Price Velocity  — % move per 1m bar (threshold-scored 0–100)
 *   2. Volume Burst    — current bar volume vs 20-bar rolling average (0–100)
 *   3. Bar Quality     — close position within the bar's range (0–100)
 *                        bullish spike: close near high = clean move, no upper wick rejection
 *                        bearish spike: close near low  = clean move, no lower wick rejection
 *   4. Index Alignment — stock direction vs NIFTY_FUT 5-bar trend (+100 / +80 / -20)
 *   5. Sector Align    — stock velocity vs NIFTY velocity (market-wide vs stock-specific) (+80 / +50 / -15)
 *   6. Rejection Wick  — penalty when bar spike reversed ≥ 80% within the 1m bar (-25 normalized)
 *
 * Final spike score = avg(components 1–5) − rejection_penalty, capped [0, 100].
 * Signal fired when spike_score ≥ 75.
 *
 * Works on 1m candle data — adapts the tick-level spec to the finest available resolution.
 * Session gate: 09:15–15:20 IST (stops 10 min before close to avoid thin tape).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
        strategyKey = "NSE_SPIKE_DETECTION",
        assetClass  = "EQUITY",
        segment     = "NSE",
        exchange    = "NSE",
        timeframe   = "1m"
)
public class NseSpikeDetectionSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String   TIMEFRAME      = "1m";
    private static final String   NIFTY_SYMBOL   = "NIFTY_FUT";
    private static final int      BARS_TO_FETCH  = 50;
    private static final int      VOL_AVG_PERIOD = 20;
    private static final int      NIFTY_BARS     = 7;
    // NIFTY_FUT data is identical for every symbol in the same scan cycle.
    // Cache it for 55s so 150 concurrent symbol evaluations share one DB fetch.
    private static final Duration NIFTY_CACHE_TTL = Duration.ofSeconds(55);

    private final MarketDataQueryService marketDataQueryService;

    // Shared across all evaluations — updated at most once per NIFTY_CACHE_TTL
    private final AtomicReference<double[]> niftyCache   = new AtomicReference<>(null);
    private volatile Instant                niftyCachedAt = Instant.EPOCH;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.spike.session.start:09:15}")
    private LocalTime sessionStart;

    @Value("${stokr.spike.session.end:15:20}")
    private LocalTime sessionEnd;

    @Value("${stokr.spike.score-threshold:75.0}")
    private double scoreThreshold;

    @Value("${stokr.spike.emit-cooldown-seconds:300}")
    private long emitCooldownSeconds;

    @Value("${stokr.spike.min-velocity-pct:0.20}")
    private double minVelocityPct;

    @Value("${stokr.spike.min-volume-multiple:2.5}")
    private double minVolumeMultiple;

    @Value("${stokr.spike.min-bar-quality:0.60}")
    private double minBarQuality;

    @Value("${stokr.spike.continuation-check:true}")
    private boolean requireContinuationConfirmation;

    @Value("${stokr.spike.wick-penalty-strict:35}")
    private int wickPenaltyStrict;

    @Value("${stokr.spike.wick-threshold-rejection:0.70}")
    private double wickThresholdRejectBlock;

    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    // ── Entry point ───────────────────────────────────────────────────────────

    @Override
    public String key() {
        return "NSE_SPIKE_DETECTION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ── 1. Session gate ───────────────────────────────────────────────────
        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
                return hold(context);
            }
        }

        // ── 2. Load stock 1m bars ─────────────────────────────────────────────
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(
                symbol, TIMEFRAME, BARS_TO_FETCH, context.asOf());

        if (bars.size() < VOL_AVG_PERIOD + 2) {
            log.debug("strategy.spike.insufficient_bars symbol={} have={}", symbol, bars.size());
            return hold(context);
        }

        int n = bars.size();
        double[] closes  = new double[n];
        double[] highs   = new double[n];
        double[] lows    = new double[n];
        double[] volumes = new double[n];
        for (int i = 0; i < n; i++) {
            MarketdataCandle c = bars.get(i);
            closes[i]  = toDouble(c.getClosePrice());
            highs[i]   = toDouble(c.getHighPrice());
            lows[i]    = toDouble(c.getLowPrice());
            volumes[i] = toDouble(c.getVolume());
        }

        double curClose  = closes[n - 1];
        double prevClose = closes[n - 2];
        double curHigh   = highs[n - 1];
        double curLow    = lows[n - 1];
        double curVol    = volumes[n - 1];

        if (curClose <= 0 || prevClose <= 0) return hold(context);

        // ── 3. Component 1 — Price Velocity ───────────────────────────────────
        double velocityPct = (curClose - prevClose) / prevClose * 100.0;
        int velScore = velocityScore(velocityPct);
        if (velScore == 0) return hold(context); // below minimum threshold — not a spike

        boolean bullish = velocityPct > 0;

        // ── 4. Component 2 — Volume Burst ─────────────────────────────────────
        double avgVol     = rollingAvg(volumes, n - 1, VOL_AVG_PERIOD);
        double volRatio   = avgVol > 0 ? curVol / avgVol : 0.0;
        int    volScore   = volumeScore(volRatio);

        // ── 5. Component 3 — Bar Quality (close position in range) ────────────
        double range         = curHigh - curLow;
        double closePosition = range > 0 ? (curClose - curLow) / range : 0.5;
        int    barScore      = barQualityScore(closePosition, bullish);

        // ── 6. Fetch NIFTY once — used for components 4 + 5 ──────────────────
        double[] niftyCloses = niftyCloses(context);

        // ── 7. Component 4 — Index Alignment ──────────────────────────────────
        int idxScore = indexAlignmentScore(niftyCloses, bullish);

        // ── 8. Component 5 — Sector Alignment ─────────────────────────────────
        int secScore = sectorAlignmentScore(niftyCloses, velocityPct);

        // ── 9. Component 6 — Rejection Wick Penalty ───────────────────────────
        // Within the bar: if price spiked up but close is far below the high (or vice versa)
        // the spike was rejected — penalty subtracts from the normalized average
        int rejPenalty = rejectionPenalty(curClose, curHigh, curLow, range, bullish);

        // ── 10. Final score ────────────────────────────────────────────────────
        double rawAvg    = (velScore + volScore + barScore + idxScore + secScore) / 5.0;
        double spikeScore = Math.max(0.0, Math.min(100.0, rawAvg - rejPenalty));

        log.debug("strategy.spike.score symbol={} vel={} vol={} bar={} idx={} sec={} rej={} score={}",
                symbol, velScore, volScore, barScore, idxScore, secScore, rejPenalty, String.format("%.1f", spikeScore));

        if (spikeScore < scoreThreshold) {
            return hold(context);
        }

        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant last = lastEmitBySymbol.get(symbol);
        if (last != null && Duration.between(last, now).getSeconds() < emitCooldownSeconds) {
            return hold(context);
        }
        lastEmitBySymbol.put(symbol, now);

        SignalType type = bullish ? SignalType.BUY : SignalType.SELL;
        String reason = String.format(
                "Spike %s score=%.1f vel=%.3f%% volRatio=%.1fx [vel:%d vol:%d bar:%d idx:%d sec:%d rej:-%d]",
                type, spikeScore, velocityPct, volRatio,
                velScore, volScore, barScore, idxScore, secScore, rejPenalty);

        log.info("strategy.spike.signal symbol={} type={} score={} reason={}",
                symbol, type, String.format("%.1f", spikeScore), reason);
        return new StrategySignal(type, symbol, BigDecimal.ONE, reason);
    }

    // ── Component 1: Price Velocity ───────────────────────────────────────────
    // INSTITUTIONAL STANDARD: Only decisive moves count as spikes
    // Noise floor raised from 0.10% → 0.20% (filters chop/noise)
    // 0.30%+/min is a decisive spike on a liquid large-cap

    private int velocityScore(double pctPerMin) {
        double abs = Math.abs(pctPerMin);
        if (abs < minVelocityPct) return 0;     // noise / normal drift (now 0.20%)
        if (abs < 0.30) return 30;              // slow move (raised from 20)
        if (abs < 0.50) return 60;              // definite spike
        if (abs < 0.80) return 80;              // strong spike
        return 100;                              // extreme (news / algo-driven)
    }

    // ── Component 2: Volume Burst ─────────────────────────────────────────────
    // INSTITUTIONAL STANDARD: Spikes require conviction volume
    // Floor raised from 1.5x → 2.5x (filters low-volume fake spikes)

    private int volumeScore(double ratio) {
        if (ratio < minVolumeMultiple) return 0;  // Below min (now 2.5x)
        if (ratio < 3.0) return 40;               // Minimum accepted (raised from 25)
        if (ratio < 4.0) return 70;               // Good volume (raised from 75)
        return 100;                               // Exceptional volume
    }

    // ── Component 3: Bar Quality ──────────────────────────────────────────────
    // For a bullish spike: close near the high = no upper-wick rejection yet = clean move.
    // For a bearish spike: close near the low  = clean momentum downward.

    private static int barQualityScore(double closePosition, boolean bullish) {
        // closePosition ∈ [0, 1]: 0 = at low, 1 = at high
        double quality = bullish ? closePosition : (1.0 - closePosition);
        if (quality >= 0.80) return 100;
        if (quality >= 0.60) return 75;
        if (quality >= 0.40) return 50;
        if (quality >= 0.20) return 25;
        return 0;
    }

    // ── Component 4: Index Alignment ─────────────────────────────────────────
    // Stock direction aligned with NIFTY trend = institutional confirmation.
    // Stock against strong NIFTY = likely reversal, heavy penalty.

    private static int indexAlignmentScore(double[] niftyCloses, boolean stockBullish) {
        if (niftyCloses == null || niftyCloses.length < 2) return 50; // neutral
        double niftyNow  = niftyCloses[niftyCloses.length - 1];
        double niftyBase = niftyCloses[0];
        if (niftyBase <= 0) return 50;
        double niftyPct     = (niftyNow - niftyBase) / niftyBase * 100.0;
        boolean niftyBullish = niftyPct > 0.0;
        double  absNifty    = Math.abs(niftyPct);
        if (stockBullish == niftyBullish) {
            return absNifty >= 0.10 ? 100 : 80; // confirmed vs flat NIFTY
        } else {
            return absNifty >= 0.10 ? -20 : 50; // against strong NIFTY vs flat
        }
    }

    // ── Component 5: Sector Alignment ────────────────────────────────────────
    // Simplified: compare stock's 1-bar velocity vs NIFTY's 1-bar velocity.
    // Same direction + NIFTY also moving = sector-wide move (more reliable).
    // Stock vs flat/opposite NIFTY = stock-specific (neutral / risky).

    private static int sectorAlignmentScore(double[] niftyCloses, double stockVelocityPct) {
        if (niftyCloses == null || niftyCloses.length < 2) return 50;
        double niftyNow  = niftyCloses[niftyCloses.length - 1];
        double niftyPrev = niftyCloses[niftyCloses.length - 2];
        if (niftyPrev <= 0) return 50;
        double niftyVelocity  = (niftyNow - niftyPrev) / niftyPrev * 100.0;
        boolean stockBullish  = stockVelocityPct > 0;
        boolean niftyBullish  = niftyVelocity > 0;
        if (stockBullish == niftyBullish) {
            return Math.abs(niftyVelocity) >= 0.05 ? 80 : 50; // sector momentum vs stock-specific
        }
        return -15; // stock against sector direction
    }

    // ── Component 6: Rejection Wick Penalty ──────────────────────────────────
    // INSTITUTIONAL STANDARD: Strong wick rejection blocks spike entirely
    // Check if within the current 1m bar the price spike was already rejected.
    // Bullish bar: large upper wick (close << high) = buyers tried but got sold into.
    // Bearish bar: large lower wick (close >> low)  = sellers tried but got bought into.
    // Penalty is normalized (spec's -50 → -35 since we average 5 components).

    private int rejectionPenalty(double close, double high, double low, double range, boolean bullish) {
        if (range <= 0) return 0;
        if (bullish) {
            double upperWick  = high - close;
            double wickRatio  = upperWick / range;
            if (wickRatio > wickThresholdRejectBlock) return 100; // Block entirely if > 70%
            if (wickRatio > 0.80) return wickPenaltyStrict;       // strong rejection (35 vs 25)
            if (wickRatio > 0.50) return 15;                      // partial rejection (raised from 10)
        } else {
            double lowerWick  = close - low;
            double wickRatio  = lowerWick / range;
            if (wickRatio > wickThresholdRejectBlock) return 100; // Block entirely if > 70%
            if (wickRatio > 0.80) return wickPenaltyStrict;       // strong rejection
            if (wickRatio > 0.50) return 15;                      // partial rejection
        }
        return 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * NIFTY_FUT 1m closes — cached for NIFTY_CACHE_TTL (55s) across all symbol evaluations.
     * With 150 symbols per scan cycle this replaces 150 identical DB fetches with one.
     */
    private double[] niftyCloses(StrategyContext context) {
        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        double[] cached = niftyCache.get();
        if (cached != null && Duration.between(niftyCachedAt, now).abs().compareTo(NIFTY_CACHE_TTL) < 0) {
            return cached;
        }
        try {
            List<MarketdataCandle> nb = marketDataQueryService.lastBarsAscEndingAt(
                    NIFTY_SYMBOL, TIMEFRAME, NIFTY_BARS, context.asOf());
            if (nb.size() < 2) return null;
            double[] arr = new double[nb.size()];
            for (int i = 0; i < nb.size(); i++) arr[i] = toDouble(nb.get(i).getClosePrice());
            niftyCache.set(arr);
            niftyCachedAt = now;
            return arr;
        } catch (Exception ex) {
            log.debug("strategy.spike.nifty_fetch_failed {}", ex.getMessage());
            return cached; // return stale data rather than null if available
        }
    }

    /** Rolling average of {@code period} values ending at (but not including) {@code endExclusive}. */
    private static double rollingAvg(double[] arr, int endExclusive, int period) {
        if (endExclusive < period) return 0.0;
        double sum = 0;
        for (int i = endExclusive - period; i < endExclusive; i++) sum += arr[i];
        return sum / period;
    }

    private static double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
