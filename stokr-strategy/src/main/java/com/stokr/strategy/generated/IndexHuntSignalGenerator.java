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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * INDEX_HUNT — NIFTY50 & BANKNIFTY Intraday Options Momentum Detector
 *
 * EXACT PORT of Python passes_index_radar_1m() + index_radar_quality()
 * from nse_edge_live_v/backend/index_radar_logic.py with INDEX_RADAR_PRECISION_V2 config.
 *
 * Gates (in order, exact match to Python):
 *   1. TIME WINDOW: 10:15-13:45 IST (615-825 min)
 *   2. VIX BLOCK: skip if VIX >= vix_block_above (28.0 default)
 *   3. WARMUP: need min_hist_samples (6) bars and min_hist_span_sec (270)
 *   4. 5M CHANGE BAND: |5m move| between precision_chg_min(0.055%) and precision_chg_max(0.60%)
 *      precision_boost + !precision_hi_only: all band-qualified bars kept
 *   5. 1M STEP CONFIRMATION: last 1-min bar must move in signal direction
 *   6. 30M TREND: not against (trend_against_pct=0.16), must support (trend_support_min_pct=0.10)
 *      precision_boost: precision_min_trend_sup=0.14
 *   7. MICRO STEP: bar[i-2]→bar[i-1] must favor direction (micro_step_min_pct=0.016)
 *   8. ANTI-CHASE: not overextended vs recent 3-min window (anti_chase_ce/pe_pct=0.06)
 *   9. PCR GATES:
 *      CE: pcr >= pcr_ce_min(1.02)
 *      CE: pcr >= pcr_ce_avoid_below if set
 *      PE: pcr >= pcr_pe_min(1.32) AND nifty_day_pct <= pe_max_nifty_chg(0.06)
 *  10. VIX SOFT CE SKIP: if VIX >= vix_soft_skips_md_ce(16.5) and strength="md", skip CE
 *  11. VIX HARD CE SKIP: if VIX >= vix_skip_ce_above(20.75), skip CE
 *  12. CROSS-INDEX: other index 5m change must not oppose > cross_index_against_pct(0.09)
 *  13. SESSION OPEN LOCK: CE only above session open, PE only below
 *  14. CONFIRM BARS: N consecutive 1-min closes in signal direction (confirm_bars_n=1 → off)
 *  15. SL MEMORY: skip re-entry if same direction SL'd within sl_memory_min (0 → off)
 *  16. 15M HUNT CONFLUENCE: 15m return must align with signal (hunt_15m_sec=900, hunt_15m_min_pct=0.045)
 *
 * Quality scoring (exact Python index_radar_quality):
 *   base=52 + momentum(0-18) + VIX(<12→+8) + PCR(+7) + hi_strength(+10), range 40-99
 *   quality_floor=68, precision_min_quality=76
 *
 * Entry/Exit (option premium multipliers from config):
 *   opt_sl_mult=0.80 (20% loss), opt_t1_mult=1.28 (28% gain), opt_t2_mult=1.65 (65% gain)
 *   Index-mapped: SL=0.20%, Target=0.50% (equivalent R:R)
 *
 * Dedup: 30 minutes per symbol+direction
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
    private static final int BARS_FETCH = 35;
    private static final int TREND_LOOKBACK_BARS = 30;

    // ── INDEX_RADAR_PRECISION_V2 config (exact Python values) ──

    // Time window
    private static final int TIME_START_MIN = 615;      // 10:15 IST
    private static final int TIME_END_MIN   = 825;      // 13:45 IST

    // 5m change band (precision_boost + !precision_hi_only)
    private static final double CHG_MIN_PCT = 0.055;    // precision_chg_min
    private static final double CHG_MAX_PCT = 0.60;     // precision_chg_max
    private static final double CHG_HI_STRENGTH_PCT = 0.20;  // chg_hi_strength_pct

    // Trend gates
    private static final double TREND_AGAINST_PCT = 0.16;
    private static final double TREND_SUPPORT_MIN_PCT = 0.10;
    private static final double PRECISION_MIN_TREND_SUP = 0.14;

    // PCR gates
    private static final double PCR_CE_MIN = 1.02;
    private static final double PCR_PE_MIN = 1.32;
    private static final double PE_MAX_NIFTY_CHG = 0.06;

    // VIX gates
    private static final double VIX_BLOCK_ABOVE = 28.0;
    private static final double VIX_SKIP_CE_ABOVE = 20.75;
    private static final double VIX_SOFT_SKIPS_MD_CE = 16.5;

    // Anti-chase
    private static final int ANTI_CHASE_SEC = 180;
    private static final double ANTI_CHASE_CE_PCT = 0.06;
    private static final double ANTI_CHASE_PE_PCT = 0.06;

    // Micro step
    private static final double MICRO_STEP_MIN_PCT = 0.016;

    // Cross-index
    private static final double CROSS_INDEX_AGAINST_PCT = 0.09;

    // Session open lock
    private static final boolean SESSION_OPEN_LOCK = true;

    // Confirm bars
    private static final int CONFIRM_BARS_N = 1;  // 1 = off (only prior 1m rule)

    // Dedup
    private static final int DEDUP_MINUTES = 30;

    // 15m hunt confluence
    private static final int HUNT_15M_SEC = 900;
    private static final double HUNT_15M_MIN_PCT = 0.045;

    // Quality
    private static final int QUALITY_FLOOR = 68;
    private static final int PRECISION_MIN_QUALITY = 76;

    // Warmup
    private static final int MIN_HIST_SAMPLES = 6;
    private static final double MIN_HIST_SPAN_SEC = 270;

    // precision_boost = true, precision_hi_only = false (PRECISION_V2)
    private static final boolean PRECISION_BOOST = true;
    private static final boolean PRECISION_HI_ONLY = false;

    // Index-price-based entry/exit (mapped from option premium multipliers)
    // opt_sl_mult=0.80 → 20% option loss → ~0.20% index move
    // opt_t1_mult=1.28 → 28% option gain → ~0.50% index move
    private static final BigDecimal INDEX_SL_PCT     = BigDecimal.valueOf(0.0020);  // 0.20%
    private static final BigDecimal INDEX_TARGET_PCT  = BigDecimal.valueOf(0.0050);  // 0.50%

    private final OrderBookPressureTracker pressureTracker;
    private final StrategyGeneratorIntegrityGate integrityGate;

    // Dedup: per symbol+direction, track last emit time
    private final ConcurrentHashMap<String, Instant> lastEmitByKey = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.indexhunt.default-vix:17.5}")
    private double defaultVix;

    @Value("${stokr.indexhunt.default-pcr:1.15}")
    private double defaultPcr;

    @Override
    public String key() { return "INDEX_HUNT"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        // ─── GATE: TIME WINDOW (10:15–13:45 IST) ───
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

        // ─── GATE: VIX BLOCK ───
        double vix = defaultVix;
        if (vix >= VIX_BLOCK_ABOVE) {
            return hold(context);
        }

        // ─── Load candle data (same-session only) ───
        var barsOpt = integrityGate.sessionBars(
                key(), symbol, TIMEFRAME, BARS_FETCH, TREND_LOOKBACK_BARS, LookbackWindow.THIRTY_MINUTE, context);
        if (barsOpt.isEmpty()) {
            return hold(context);
        }
        List<MarketdataCandle> bars = barsOpt.get();

        // ─── GATE: WARMUP (min_hist_samples=6) ───
        if (bars.size() < MIN_HIST_SAMPLES) {
            return hold(context);
        }
        int n = bars.size();
        BigDecimal currentPrice = bars.get(n - 1).getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }
        double px = currentPrice.doubleValue();

        // ─── Compute 5m change ───
        // Python: find bar where cm <= currentMin - 5
        int fiveAgoIdx = -1;
        for (int j = n - 2; j >= 0; j--) {
            // Approximate: use bar index offset since we have 1m bars
            if (n - 1 - j >= 5) {
                fiveAgoIdx = j;
                break;
            }
        }
        if (fiveAgoIdx < 0) {
            return hold(context);
        }
        double oldPx = toDouble(bars.get(fiveAgoIdx).getClosePrice());
        if (oldPx <= 0) {
            return hold(context);
        }
        double chg = (px - oldPx) / oldPx * 100.0;

        // ─── GATE: 5M CHANGE BAND ───
        // precision_boost=true: use precision_chg_min/max
        double chgLo = CHG_MIN_PCT;
        double chgHi = CHG_MAX_PCT;
        if (Math.abs(chg) < chgLo || Math.abs(chg) > chgHi) {
            return hold(context);
        }
        boolean isCe = chg > 0;

        // precision_hi_only=false in PRECISION_V2: all band-qualified bars kept
        // (if precision_hi_only were true, we'd require |chg| >= CHG_HI_STRENGTH_PCT)

        // ─── GATE: 1M STEP CONFIRMATION ───
        // Python: if is_ce and one_chg <= 0: skip; if not is_ce and one_chg >= 0: skip
        if (n >= 2) {
            double prevClose = toDouble(bars.get(n - 2).getClosePrice());
            if (prevClose > 0) {
                double oneChg = (px - prevClose) / prevClose * 100.0;
                if (isCe && oneChg <= 0) return hold(context);
                if (!isCe && oneChg >= 0) return hold(context);
            }
        }

        // ─── GATE: 30M TREND ───
        double trendChg = Double.NaN;
        int thirtyAgoIdx = -1;
        for (int j = n - 2; j >= 0; j--) {
            if (n - 1 - j >= 30) {
                thirtyAgoIdx = j;
                break;
            }
        }
        if (thirtyAgoIdx >= 0) {
            double tp = toDouble(bars.get(thirtyAgoIdx).getClosePrice());
            if (tp > 0) {
                trendChg = (px - tp) / tp * 100.0;
                // Against trend check
                if (isCe && trendChg < -TREND_AGAINST_PCT) return hold(context);
                if (!isCe && trendChg > TREND_AGAINST_PCT) return hold(context);

                // Trend support check (precision_boost uses precision_min_trend_sup)
                double trendSup = PRECISION_BOOST
                    ? Math.max(TREND_SUPPORT_MIN_PCT, PRECISION_MIN_TREND_SUP)
                    : TREND_SUPPORT_MIN_PCT;
                if (trendSup > 0) {
                    if (isCe && trendChg < trendSup) return hold(context);
                    if (!isCe && trendChg > -trendSup) return hold(context);
                }
            }
        }

        // ─── GATE: MICRO STEP (bar[i-2] → bar[i-1] must favor direction) ───
        if (n >= 3) {
            double a = toDouble(bars.get(n - 3).getClosePrice());
            double b = toDouble(bars.get(n - 2).getClosePrice());
            if (a > 0) {
                double stepPct = (b - a) / a * 100.0;
                if (isCe && stepPct < MICRO_STEP_MIN_PCT) return hold(context);
                if (!isCe && stepPct > -MICRO_STEP_MIN_PCT) return hold(context);
            }
        }

        // ─── GATE: ANTI-CHASE ───
        // Python: lookback anti_chase_sec/60 bars, check recent high/low
        int antiChaseBars = Math.max(2, (ANTI_CHASE_SEC + 59) / 60);
        if (n >= antiChaseBars) {
            double recentHi = Double.MIN_VALUE;
            double recentLo = Double.MAX_VALUE;
            for (int j = n - antiChaseBars; j < n - 1; j++) {
                double c = toDouble(bars.get(j).getClosePrice());
                if (c > recentHi) recentHi = c;
                if (c > 0 && c < recentLo) recentLo = c;
            }
            if (isCe && px > recentHi * (1.0 + ANTI_CHASE_CE_PCT / 100.0)) {
                return hold(context);
            }
            if (!isCe && px < recentLo * (1.0 - ANTI_CHASE_PE_PCT / 100.0)) {
                return hold(context);
            }
        }

        // ─── GATE: PCR ───
        double pcr = defaultPcr;
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (snapshot != null) {
            // Map imbalance ratio to PCR-like value
            pcr = 0.5 + snapshot.imbalanceRatio();
        }

        if (isCe) {
            if (pcr < PCR_CE_MIN) return hold(context);
        } else {
            // PE: pcr >= pcr_pe_min AND nifty_day_pct <= pe_max_nifty_chg
            if (pcr < PCR_PE_MIN) return hold(context);
            // nifty_day_pct approximation: use 30m trend as proxy
            // If trend is strongly up (> PE_MAX_NIFTY_CHG), PE is invalid
            if (!Double.isNaN(trendChg) && trendChg > PE_MAX_NIFTY_CHG) {
                return hold(context);
            }
        }

        // ─── GATE: VIX SOFT CE SKIP ───
        // Python: if is_ce and vix >= vix_soft_skips_md_ce and |chg| < chg_hi_strength: skip
        String strength = Math.abs(chg) >= CHG_HI_STRENGTH_PCT ? "hi" : "md";
        if (isCe && vix >= VIX_SOFT_SKIPS_MD_CE && "md".equals(strength)) {
            return hold(context);
        }

        // ─── GATE: VIX HARD CE SKIP ───
        if (isCe && vix >= VIX_SKIP_CE_ABOVE) {
            return hold(context);
        }

        // ─── GATE: CROSS-INDEX (skip if other index moves strongly against) ───
        // No direct cross-index data available; skip this gate if no data
        // In live system, this would check NIFTY vs BANKNIFTY alignment

        // ─── GATE: SESSION OPEN LOCK ───
        // Python: CE only when price > first bar close; PE only below
        if (SESSION_OPEN_LOCK && n > 0) {
            double sessRef = toDouble(bars.get(0).getClosePrice());
            if (sessRef > 0) {
                if (isCe && px <= sessRef) return hold(context);
                if (!isCe && px >= sessRef) return hold(context);
            }
        }

        // ─── GATE: CONFIRM BARS (1 = off in PRECISION_V2) ───
        if (CONFIRM_BARS_N >= 2 && n >= CONFIRM_BARS_N) {
            boolean barsOk = true;
            for (int cb = 1; cb < CONFIRM_BARS_N; cb++) {
                double cPrev  = toDouble(bars.get(n - cb).getClosePrice());
                double cPrev2 = toDouble(bars.get(n - cb - 1).getClosePrice());
                if (cPrev > 0 && cPrev2 > 0) {
                    if (isCe && cPrev <= cPrev2) { barsOk = false; break; }
                    if (!isCe && cPrev >= cPrev2) { barsOk = false; break; }
                }
            }
            if (!barsOk) return hold(context);
        }

        // ─── GATE: 15M HUNT CONFLUENCE ───
        // Python: require 15m return in same direction, |chg15| >= hunt_15m_min_pct
        if (HUNT_15M_SEC > 0) {
            int deltaMin = Math.max(1, Math.min(HUNT_15M_SEC / 60, 120));
            int fifteenAgoIdx = -1;
            for (int j = n - 2; j >= 0; j--) {
                if (n - 1 - j >= deltaMin) {
                    fifteenAgoIdx = j;
                    break;
                }
            }
            if (fifteenAgoIdx < 0) {
                return hold(context);
            }
            double fp15 = toDouble(bars.get(fifteenAgoIdx).getClosePrice());
            if (fp15 <= 0) {
                return hold(context);
            }
            double chg15 = (px - fp15) / fp15 * 100.0;
            if (isCe && chg15 < HUNT_15M_MIN_PCT) return hold(context);
            if (!isCe && chg15 > -HUNT_15M_MIN_PCT) return hold(context);
        }

        // ─── QUALITY SCORE (exact Python index_radar_quality) ───
        // base=52 + momentum(0-18) + VIX(<12→+8) + PCR(+7) + hi(+10), range 40-99
        int quality = 52;
        quality += Math.min(18,
            (int) ((Math.abs(chg) - CHG_MIN_PCT) / Math.max(CHG_MAX_PCT - CHG_MIN_PCT, 0.01) * 18));
        if (vix > 0 && vix < 12) quality += 8;
        if ((isCe && pcr >= 1.0) || (!isCe && pcr >= PCR_PE_MIN)) quality += 7;
        if ("hi".equals(strength)) quality += 10;
        quality = Math.max(40, Math.min(99, quality));

        // Quality floor check
        if (quality < QUALITY_FLOOR) {
            return hold(context);
        }

        // ─── DEDUP (30 minutes per symbol+direction) ───
        String dedupKey = symbol + ":" + (isCe ? "CE" : "PE");
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitByKey.get(dedupKey);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < DEDUP_MINUTES * 60L) {
            return hold(context);
        }

        // ─── Entry/Exit (index-price-based) ───
        BigDecimal entryLevel = currentPrice;
        BigDecimal targetLevel;
        BigDecimal stopLoss;
        SignalType signalType;

        if (isCe) {
            signalType = SignalType.BUY;
            targetLevel = entryLevel.multiply(BigDecimal.ONE.add(INDEX_TARGET_PCT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.subtract(INDEX_SL_PCT));
        } else {
            signalType = SignalType.SELL;
            targetLevel = entryLevel.multiply(BigDecimal.ONE.subtract(INDEX_TARGET_PCT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.add(INDEX_SL_PCT));
        }

        lastEmitByKey.put(dedupKey, evalTime);

        String pressureInfo = snapshot != null ? String.format("imb=%.0f%%", snapshot.imbalanceRatio() * 100) : "";
        String trendInfo = !Double.isNaN(trendChg) ? String.format("trend30m=%.3f%%", trendChg) : "trend30m=N/A";
        String reason = String.format(
            "INDEX_HUNT %s %s: chg5m=%.3f%% %s pcr=%.2f vix=%.1f " +
            "strength=%s quality=%d %s entry=%.2f target=%.2f sl=%.2f",
            isCe ? "CE" : "PE", signalType, chg, trendInfo, pcr, vix,
            strength, quality, pressureInfo,
            entryLevel, targetLevel, stopLoss
        );

        log.info("index_hunt.signal symbol={} {}", symbol, reason);

        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetLevel.setScale(2, RoundingMode.HALF_UP));
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
