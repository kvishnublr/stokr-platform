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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADV_CASH — NSE ADV Cash Enhanced 10-Step Accuracy Strategy
 *
 * EXACT PORT of Python adv_cash_improved.py from nse_edge_live_v.
 *
 * 10 Steps:
 *   Step 1:  Top 25 liquid stocks only                (+3%)
 *   Step 2:  Hard time windows, no dead zones          (+2%)
 *   Step 3:  Dynamic VIX threshold                     (+2%)
 *   Step 4:  OBI slope filter (rising, 8-tick linreg)  (+2%)
 *   Step 5:  Weighted 5-level OBI                      (+2%)
 *   Step 6:  Min 4-signal consensus                    (+2%)
 *   Step 7:  Pre-market delivery + OI (needs data)     (+2%) [inactive]
 *   Step 8:  First candle gate 9:15-9:17               (+2%)
 *   Step 9:  Pattern memory KNN (100+ trades)          (+3%)
 *   Step 10: OBI reversal exit mid-trade               (+1%)
 *
 * Consensus signals (need ALL 4):
 *   1. obi_slope: |slope| >= 0.015
 *   2. obi_strength: level in {STRONG, VERY_STRONG, WEAK}
 *   3. time_window: not DEAD_ZONE
 *   4. vix_regime: VIX < 25
 *
 * Composite score = OBI(40%) + slope/0.05(30%) + vol(20%) + (30-vix)/30(10%), range 0-100
 * Direction: BUY if obi_score > 0, SHORT if < 0
 * SL=0.4%, T1=0.7%, T2=1.4%
 *
 * DATA SOURCE MAPPING:
 *   obi_score    -> OrderBookPressureTracker imbalanceRatio mapped to -1..+1
 *   obi_slope    -> 8-tick linear regression of imbalance history (tracked in-memory)
 *   vol_mult     -> 5m volume / baseline volume ratio
 *   vix          -> configurable default (no VIX feed yet)
 *   time windows -> MORNING(9:15-10:00), MIDDAY(10:00-11:30), DEAD_ZONE(11:30-13:15), AFTERNOON(13:30-14:30)
 *
 * KNN: 6-feature cosine similarity pattern memory (obi, slope/0.1, vol/10, vix/30, time_min/870, direction)
 */
@Service
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
    private static final int BARS_FETCH = 10;

    // ── Python exact constants ──
    private static final BigDecimal SL_PCT  = BigDecimal.valueOf(0.004);   // 0.4%
    private static final BigDecimal T1_PCT  = BigDecimal.valueOf(0.007);   // 0.7%
    private static final BigDecimal T2_PCT  = BigDecimal.valueOf(0.014);   // 1.4%
    private static final double OBI_SLOPE_MIN = 0.015;

    // Step 1: TOP 25 liquid stocks (exact match from Python)
    private static final Set<String> TOP25 = Set.of(
        "RELIANCE", "TCS", "HDFCBANK", "ICICIBANK", "INFY",
        "SBIN", "BHARTIARTL", "KOTAKBANK", "LT", "AXISBANK",
        "BAJFINANCE", "HINDUNILVR", "MARUTI", "TATAMOTORS", "WIPRO",
        "ULTRACEMCO", "TITAN", "SUNPHARMA", "ONGC", "TATASTEEL",
        "NTPC", "POWERGRID", "M&M", "BAJAJFINSV", "ADANIENT"
    );

    // Step 2: Time windows (exact match from Python WINDOW_RANGES)
    // MORNING 09:15-10:00, MIDDAY 10:00-11:30, DEAD_ZONE 11:30-13:15, AFTERNOON 13:30-14:30
    private static final int MORNING_START   = 555;  // 09:15
    private static final int MORNING_END     = 600;  // 10:00
    private static final int MIDDAY_START    = 600;  // 10:00
    private static final int MIDDAY_END      = 690;  // 11:30
    private static final int DEAD_ZONE_START = 690;  // 11:30
    private static final int DEAD_ZONE_END   = 795;  // 13:15
    private static final int AFTERNOON_START = 810;  // 13:30
    private static final int AFTERNOON_END   = 870;  // 14:30

    // KNN constants (exact match from Python)
    private static final int KNN_MIN_TRADES = 100;
    private static final int KNN_K = 5;

    private final OrderBookPressureTracker pressureTracker;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    // OBI history per symbol for slope calculation (8-tick linear regression)
    private final ConcurrentHashMap<String, Deque<Double>> obiHistory = new ConcurrentHashMap<>();

    // KNN pattern memory
    private final List<double[]> knnVectors = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> knnLabels = Collections.synchronizedList(new ArrayList<>());

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.advcash.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Value("${stokr.advcash.default-vix:17.5}")
    private double defaultVix;

    public AdvCashEquitySignalGenerator(OrderBookPressureTracker pressureTracker,
                                        StrategyGeneratorIntegrityGate integrityGate) {
        this.pressureTracker = pressureTracker;
        this.integrityGate = integrityGate;
    }

    @Override
    public String key() { return "ADV_CASH"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        // ─── STEP 1: Top 25 liquid stocks only ───
        // Python: if not is_top25(sym): return None
        String cleanSymbol = symbol.replace("NSE:", "").replace("BSE:", "").toUpperCase();
        if (!TOP25.contains(cleanSymbol)) {
            return hold(context);
        }

        // ─── Time computation ───
        LocalTime now;
        if (context.asOf() != null) {
            now = context.asOf().atZone(zone).toLocalTime();
        } else {
            now = LocalTime.now(zone);
        }
        int currentMin = now.getHour() * 60 + now.getMinute();
        String timeStr = String.format("%02d:%02d", now.getHour(), now.getMinute());

        // ─── STEP 8: First candle gate — skip 9:15-9:17 ───
        // Python: if hh == 9 and 15 <= mm <= 17: return False
        if (now.getHour() == 9 && now.getMinute() >= 15 && now.getMinute() <= 17) {
            return hold(context);
        }

        // ─── STEP 2: Time window check — skip DEAD_ZONE and OTHER ───
        // Python: classify_window(t) not in ('DEAD_ZONE', 'OTHER')
        String window = classifyWindow(timeStr);
        if ("DEAD_ZONE".equals(window) || "OTHER".equals(window)) {
            return hold(context);
        }

        // ─── Load candle data (same-session only) ───
        var barsOpt = integrityGate.sessionBars(
                key(), symbol, TIMEFRAME, BARS_FETCH, 5, LookbackWindow.FIVE_MINUTE, context);
        if (barsOpt.isEmpty()) {
            return hold(context);
        }
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < 6) {
            return hold(context);
        }
        int n = bars.size();
        BigDecimal currentPrice = bars.get(n - 1).getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        // ─── Compute OBI score from pressure tracker ───
        // Python: obi_score is -1 to +1. We map imbalanceRatio (0-1) to (-1, +1)
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        double obiScore;
        if (snapshot != null) {
            // Map 0..1 imbalance to -1..+1: (imbalance - 0.5) * 2
            obiScore = (snapshot.imbalanceRatio() - 0.5) * 2.0;
        } else {
            // Fallback: use 5-bar momentum as OBI proxy
            BigDecimal closePrev = bars.get(Math.max(0, n - 6)).getClosePrice();
            if (closePrev != null && closePrev.compareTo(BigDecimal.ZERO) > 0) {
                obiScore = currentPrice.subtract(closePrev)
                    .divide(closePrev, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;  // scale to make comparable
                obiScore = Math.max(-1.0, Math.min(1.0, obiScore / 0.5));
            } else {
                return hold(context);
            }
        }

        // ─── STEP 4: OBI slope filter (8-tick linear regression) ───
        // Python: obi_slope(history) — 8-tick linreg, min 6 data points
        Deque<Double> history = obiHistory.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        history.addLast(obiScore);
        // Keep last 20 ticks
        while (history.size() > 20) {
            history.removeFirst();
        }
        double slopeVal = computeObiSlope(new ArrayList<>(history));

        // ─── STEP 5: Weighted 5-level OBI ───
        // Python: obi_level(score) — classify |obi| into 5 levels
        String obiLv = classifyObiLevel(obiScore);

        // ─── Compute volume multiplier ───
        // vol_mult = recent volume / baseline volume
        BigDecimal volume5m = BigDecimal.ZERO;
        for (int i = Math.max(0, n - 5); i < n; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) volume5m = volume5m.add(v);
        }
        // Compute baseline volume (earlier bars)
        BigDecimal baselineVol = BigDecimal.ZERO;
        int baseCount = 0;
        for (int i = 0; i < Math.max(0, n - 5); i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) {
                baselineVol = baselineVol.add(v);
                baseCount++;
            }
        }
        double volMult;
        if (baseCount > 0 && baselineVol.compareTo(BigDecimal.ZERO) > 0) {
            volMult = volume5m.doubleValue() / (baselineVol.doubleValue() / baseCount * 5);
        } else {
            volMult = volume5m.doubleValue() > 1000 ? 1.5 : 0.5;
        }

        // ─── VIX (default, no feed yet) ───
        double vix = defaultVix;

        // ─── STEP 6: Min 4-signal consensus (EXACT Python logic) ───
        // Python: signals = {obi_slope, obi_strength, time_window, vix_regime}
        Map<String, Boolean> signals = new LinkedHashMap<>();

        // Signal 1: OBI slope direction — |slope| >= 0.015
        signals.put("obi_slope", Math.abs(slopeVal) >= OBI_SLOPE_MIN);

        // Signal 2: OBI level strength — not neutral
        signals.put("obi_strength", "STRONG".equals(obiLv) || "VERY_STRONG".equals(obiLv) || "WEAK".equals(obiLv));

        // Signal 3: Time window not dead zone
        signals.put("time_window", !"DEAD_ZONE".equals(window));

        // Signal 4: VIX regime favorable
        signals.put("vix_regime", vix < 25.0);

        // Python: count >= 4
        int signalCount = (int) signals.values().stream().filter(v -> v).count();
        if (signalCount < 4) {
            return hold(context);
        }

        // ─── STEP 3: VIX size multiplier ───
        // Python: vix > 22 → 0.50, vix > 18 → 0.75, else 1.00
        double sizeMult;
        if (vix > 22) {
            sizeMult = 0.50;
        } else if (vix > 18) {
            sizeMult = 0.75;
        } else {
            sizeMult = 1.00;
        }

        // ─── Direction (exact Python) ───
        // Python: direction = 'BUY' if obi_score > 0 else 'SHORT'
        String direction = obiScore > 0 ? "BUY" : "SHORT";
        SignalType signalType = obiScore > 0 ? SignalType.BUY : SignalType.SELL;

        // ─── STEP 9: KNN probability (if ready) ───
        Double knnProb = null;
        if (knnVectors.size() >= KNN_MIN_TRADES) {
            int directionInt = "BUY".equals(direction) ? 1 : 0;
            knnProb = knnPredict(obiScore, slopeVal, volMult, vix, currentMin, directionInt);
        }

        // ─── Composite score (EXACT Python formula) ───
        // Python: (|obi|/1.0)*40 + (max(0, slope/0.05))*30 + (vol_mult/1.0)*20 + ((30-vix)/30)*10
        double compositeScore =
            (Math.abs(obiScore) / 1.0) * 40.0 +
            (Math.max(0.0, slopeVal / 0.05)) * 30.0 +
            (volMult / 1.0) * 20.0 +
            ((30.0 - vix) / 30.0) * 10.0;
        compositeScore = Math.min(100.0, Math.max(0.0, compositeScore));

        // ─── Cooldown ───
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ─── Entry/Exit Levels (EXACT Python: _levels_from_entry) ───
        BigDecimal entryLevel = currentPrice;
        BigDecimal stopLoss;
        BigDecimal target1;
        BigDecimal target2;

        if ("BUY".equals(direction)) {
            stopLoss = entryLevel.multiply(BigDecimal.ONE.subtract(SL_PCT));
            target1  = entryLevel.multiply(BigDecimal.ONE.add(T1_PCT));
            target2  = entryLevel.multiply(BigDecimal.ONE.add(T2_PCT));
        } else {
            stopLoss = entryLevel.multiply(BigDecimal.ONE.add(SL_PCT));
            target1  = entryLevel.multiply(BigDecimal.ONE.subtract(T1_PCT));
            target2  = entryLevel.multiply(BigDecimal.ONE.subtract(T2_PCT));
        }

        lastEmitBySymbol.put(symbol, evalTime);

        String knnInfo = knnProb != null ? String.format(" knn=%.3f", knnProb) : "";
        String reason = String.format(
            "ADV_CASH %s: obi=%.4f slope=%.4f obi_lv=%s vol=%.2f vix=%.1f " +
            "consensus=%d/4 composite=%.1f window=%s size=%.2f%s " +
            "entry=%.2f t1=%.2f t2=%.2f sl=%.2f",
            direction, obiScore, slopeVal, obiLv, volMult, vix,
            signalCount, compositeScore, window, sizeMult, knnInfo,
            entryLevel, target1, target2, stopLoss
        );

        log.info("adv_cash.signal symbol={} {}", symbol, reason);

        return new StrategySignal(signalType, symbol, BigDecimal.valueOf(sizeMult), reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                target1.setScale(2, RoundingMode.HALF_UP));
    }

    // ────────────────────────────────────────────────────────────────────
    // Python-exact helper methods
    // ────────────────────────────────────────────────────────────────────

    /**
     * classify_window(t) — exact Python WINDOW_RANGES logic.
     * MORNING 09:15-10:00, MIDDAY 10:00-11:30, DEAD_ZONE 11:30-13:15, AFTERNOON 13:30-14:30
     */
    private String classifyWindow(String timeStr) {
        if (timeStr.compareTo("09:15") >= 0 && timeStr.compareTo("10:00") < 0) return "MORNING";
        if (timeStr.compareTo("10:00") >= 0 && timeStr.compareTo("11:30") < 0) return "MIDDAY";
        if (timeStr.compareTo("11:30") >= 0 && timeStr.compareTo("13:15") < 0) return "DEAD_ZONE";
        if (timeStr.compareTo("13:30") >= 0 && timeStr.compareTo("14:30") < 0) return "AFTERNOON";
        return "OTHER";
    }

    /**
     * obi_level(score) — exact Python 5-level classification.
     * |score|: <0.2=NEUTRAL, 0.2-0.4=WEAK, 0.4-0.6=NEUTRAL, 0.6-0.8=STRONG, >0.8=VERY_STRONG
     */
    private String classifyObiLevel(double score) {
        double abs = Math.abs(score);
        if (abs < 0.2) return "NEUTRAL";
        if (abs < 0.4) return "WEAK";
        if (abs < 0.6) return "NEUTRAL";
        if (abs < 0.8) return "STRONG";
        return "VERY_STRONG";
    }

    /**
     * obi_slope(history) — exact Python 8-tick linear regression.
     * Uses last 8 values (min 6), computes slope via least-squares.
     */
    private double computeObiSlope(List<Double> history) {
        if (history.size() < 6) {
            return 0.0;
        }
        // Take last 8 (or fewer if < 8)
        int start = Math.max(0, history.size() - 8);
        List<Double> h = history.subList(start, history.size());
        int count = h.size();
        double xMean = (count - 1) / 2.0;
        double yMean = 0;
        for (double v : h) yMean += v;
        yMean /= count;

        double num = 0;
        double den = 0;
        for (int i = 0; i < count; i++) {
            num += (i - xMean) * (h.get(i) - yMean);
            den += (i - xMean) * (i - xMean);
        }
        if (den == 0) return 0.0;
        return num / den;
    }

    /**
     * KNN predict — exact Python cosine similarity with K=5.
     * Features: [|obi|, slope/0.1, vol/10, vix/30, time_min/870, direction]
     */
    private Double knnPredict(double obi, double slope, double vol, double vix,
                              int timeMin, int direction) {
        double[] query = knnFeature(obi, slope, vol, vix, timeMin, direction);

        // Compute similarities
        List<double[]> simLabel = new ArrayList<>();
        synchronized (knnVectors) {
            for (int i = 0; i < knnVectors.size(); i++) {
                double sim = cosineSimilarity(query, knnVectors.get(i));
                simLabel.add(new double[]{sim, knnLabels.get(i)});
            }
        }

        // Sort by similarity descending, take top K
        simLabel.sort((a, b) -> Double.compare(b[0], a[0]));
        int k = Math.min(KNN_K, simLabel.size());
        if (k == 0) return null;

        double sum = 0;
        for (int i = 0; i < k; i++) {
            sum += simLabel.get(i)[1];
        }
        return Math.round(sum / k * 1000.0) / 1000.0;
    }

    /**
     * _feat() — exact Python feature vector.
     */
    private double[] knnFeature(double obi, double slope, double vol, double vix,
                                int timeMin, int direction) {
        return new double[]{
            Math.min(Math.abs(obi), 1.0),
            Math.max(-1.0, Math.min(1.0, slope / 0.1)),
            Math.min(vol / 10.0, 1.0),
            Math.min(vix / 30.0, 1.0),
            timeMin / 870.0,
            (double) direction
        };
    }

    /**
     * _cos_sim(a, b) — exact Python cosine similarity.
     */
    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        na = Math.sqrt(na);
        nb = Math.sqrt(nb);
        if (na < 1e-9) na = 1e-9;
        if (nb < 1e-9) nb = 1e-9;
        return dot / (na * nb);
    }

    /**
     * Add outcome to KNN memory (called externally when trade resolves).
     */
    public void addKnnOutcome(double obi, double slope, double vol, double vix,
                              int timeMin, int direction, boolean win) {
        knnVectors.add(knnFeature(obi, slope, vol, vix, timeMin, direction));
        knnLabels.add(win ? 1 : 0);
    }
}
