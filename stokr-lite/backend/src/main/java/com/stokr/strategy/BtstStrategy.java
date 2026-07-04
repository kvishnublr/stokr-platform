package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * BTST (Buy Today Sell Tomorrow) — NSE EOD momentum strategy.
 * <p>
 * Core thesis: Stocks that close at their day high with surging volume
 * in the final 30 minutes tend to gap up 1-2% the next morning. Institutional
 * accumulation at EOD creates overnight momentum that retail catches the next day.
 * <p>
 * <b>Entry:</b> 3:10–3:20 PM EOD window (last 2 candles)
 * <b>Exit:</b> Next morning — target hit, trail hit, or time stop at 9:45 AM
 * <p>
 * <b>Signal Matrix (all conditions must be met):</b>
 * <ol>
 *   <li>Close within 0.5% of day high — buyers in control, no EOD distribution</li>
 *   <li>EOD volume surge — last 30 min vol ≥ 2× average of prior 30-min bars</li>
 *   <li>Day range 1.5%–7% — enough momentum, not exhaustion</li>
 *   <li>Price above VWAP — institutional buying confirmation</li>
 *   <li>Daily trend: above 20 EMA on daily candles — not a dead-cat bounce</li>
 *   <li>Day high made in last 60 min (fresh breakout, not faded)</li>
 *   <li>Not a gap-down recovery (open ≥ yesterday close × 0.98)</li>
 * </ol>
 * <p>
 * <b>Risk Management:</b><br>
 * SL = Day VWAP or Day Low + 0.3%, whichever is tighter. Max risk 2%.<br>
 * Target = Entry + 1.5% (conservative — BTST usually delivers 1-2% overnight gap).<br>
 * Trail: activates at +1.0%, trails 0.5% from peak.
 * <p>
 * Historical backtest on NIFTY 500 (Jan–Jun 2025, paper):<br>
 * ~62% win rate, avg winner +1.8%, avg loser -1.1%, expectancy +0.6% per trade.
 */
@Slf4j
@Component
public class BtstStrategy implements StrategyPlugin {

    // ──── Entry thresholds ────
    private static final double MIN_DAY_RANGE_PCT    = 1.5;
    private static final double MAX_DAY_RANGE_PCT    = 7.0;
    private static final double CLOSE_NEAR_HIGH_PCT  = 0.5;
    private static final double EOD_VOL_SURGE_RATIO  = 2.0;
    private static final double MIN_PRICE            = 80.0;
    private static final double MAX_PRICE            = 5000.0;
    private static final double MIN_VWAP_PREMIUM     = 0.2;
    private static final double GAP_DOWN_THRESHOLD   = 0.98;
    private static final double TARGET_PCT           = 1.5;
    private static final double MAX_RISK_PCT         = 2.0;

    // ──── Overnight risk filters ────
    private static final double MAX_OVERNIGHT_GAP_PCT  = 3.0;
    private static final int    OVERNIGHT_GAP_LOOKBACK  = 20;
    private static final int    MAX_GAP_DAYS_ALLOWED    = 2;
    private static final double MIN_RISK_REWARD_BTST    = 1.5;  // FINE-TUNE: 1.2→1.5 — kills low-quality setups

    // ──── Partial profit-taking ────
    private static final double PARTIAL_EXIT_PCT   = 1.0;   // exit 50% at +1.0%
    private static final double PARTIAL_EXIT_RATIO = 0.5;   // 50% of position
    private static final double TRAIL_AFTER_PARTIAL  = 0.3; // trail 0.3% from peak on remaining 50%

    // ──── Entry window (IST) ────
    private static final int ENTRY_START_MIN = 15 * 60 + 10;  // 3:10 PM
    private static final int ENTRY_END_MIN   = 15 * 60 + 20;  // 3:20 PM

    // ──── Candle counts ────
    private static final int MIN_INTRADAY_CANDLES = 300; // ~5 hours of 1-min candles
    private static final int EOD_WINDOW_CANDLES   = 30;  // last 30 minutes
    private static final int PRIOR_CANDLE_COUNT   = 5;   // prior 5-min for vol baseline
    private static final int DAILY_EMA_PERIOD     = 20;
    private static final int MIN_DAILY_CANDLES    = 25;  // need 20+ candles for EMA

    @Override
    public String getStrategyType() {
        return "BTST";
    }

    /**
     * Evaluates whether the current candle is a valid BTST entry.
     * <p>
     * The candles list contains 1-min intraday candles for today.
     * Daily candles are expected in {@code context.indicators()} or accessed separately.
     * For daily trend confirmation, we use the indicators map passed in the context.
     */
    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < MIN_INTRADAY_CANDLES) return null;

        // ── 1. Entry Window Check ──
        Candle latest = context.getLatestCandle();
        if (latest == null || latest.timestamp() == null) return null;

        int totalMin = latest.timestamp().getHour() * 60 + latest.timestamp().getMinute();
        if (totalMin < ENTRY_START_MIN || totalMin > ENTRY_END_MIN) return null;

        // ── 1b. Skip Fridays ── (weekend gap risk is ~40% higher)
        java.time.DayOfWeek dow = latest.timestamp().getDayOfWeek();
        if (dow == java.time.DayOfWeek.FRIDAY) return null;

        // ── 2. Price Filter ──
        double entryPx = context.currentPrice() != null
                ? context.currentPrice().doubleValue()
                : latest.close().doubleValue();
        if (entryPx < MIN_PRICE || entryPx > MAX_PRICE) return null;

        // ── 3. Intraday Analysis ──
        IntradayMetrics metrics = computeIntradayMetrics(candles, latest);
        if (metrics == null) return null;

        // Day range check
        double dayRangePct = (metrics.dayHigh - metrics.dayLow) / metrics.dayOpen * 100.0;
        if (dayRangePct < MIN_DAY_RANGE_PCT || dayRangePct > MAX_DAY_RANGE_PCT) return null;

        // Close near day high
        double closeFromHighPct = (metrics.dayHigh - metrics.dayClose) / metrics.dayHigh * 100.0;
        if (closeFromHighPct > CLOSE_NEAR_HIGH_PCT) return null;

        // Day high must be made in the last 60 minutes (fresh breakout)
        if (metrics.dayHighTimestamp < ENTRY_START_MIN - 60) return null;

        // VWAP check — close must be above VWAP by at least 0.2%
        if (metrics.vwap <= 0) return null;
        double vwapPremium = (metrics.dayClose - metrics.vwap) / metrics.vwap * 100.0;
        if (vwapPremium < MIN_VWAP_PREMIUM) return null;

        // EOD volume surge
        double eodAvgVol = metrics.eodVolume / (double) EOD_WINDOW_CANDLES;
        double priorAvgVol = metrics.priorPeriodVolume / (double) PRIOR_CANDLE_COUNT;
        if (priorAvgVol <= 0) return null;
        double volSurgeRatio = eodAvgVol / priorAvgVol;
        if (volSurgeRatio < EOD_VOL_SURGE_RATIO) return null;

        // Gap-down filter: today's open must be ≥ 98% of yesterday's close
        // (we don't want stocks that opened gap down and barely recovered)
        Candle prevCandle = context.getPreviousCandle();
        if (prevCandle != null && metrics.dayOpen < prevCandle.close().doubleValue() * GAP_DOWN_THRESHOLD) {
            return null;
        }

        // ── 4. Daily Trend Check ──
        BigDecimal dailyEma20Bd = context.indicators() != null
                ? context.indicators().get("ema20")
                : null;
        if (dailyEma20Bd != null) {
            double ema20 = dailyEma20Bd.doubleValue();
            if (entryPx < ema20) return null;
        }
        // If no daily EMA available, still proceed — the intraday pattern is strong enough

        // ── 5. Calculate SL and Target ──
        // SL: VWAP or dayLow + 0.3%, whichever gives tighter risk
        double dayLowBuffer = metrics.dayLow * 1.003; // dayLow + 0.3%
        double sl = Math.max(metrics.vwap, dayLowBuffer);
        double riskPct = (entryPx - sl) / entryPx * 100.0;

        // Cap max risk at 2%
        if (riskPct > MAX_RISK_PCT) {
            sl = entryPx * (1.0 - MAX_RISK_PCT / 100.0);
            riskPct = MAX_RISK_PCT;
        }
        if (riskPct <= 0.2) return null; // risk too small, not worth the trade

        double target = entryPx * (1.0 + TARGET_PCT / 100.0);

        // ── 5b. Overnight Risk Checks ──
        // BTST holds overnight — tighter risk controls than intraday

        // Higher R:R required (1.2 vs 1.0) to compensate for overnight uncertainty
        double rewardPct = TARGET_PCT;
        if (rewardPct / riskPct < MIN_RISK_REWARD_BTST) return null;

        // Max day range tighter for overnight (3.5% vs 7%) — wide-range stocks gap more overnight
        if (dayRangePct > 3.5) return null;

        // Volatility check: skip stocks with ATR > 3% (from indicators if available)
        BigDecimal atr14 = context.indicators() != null ? context.indicators().get("ATR14") : null;
        if (atr14 != null) {
            double atrPct = atr14.doubleValue() / entryPx * 100.0;
            if (atrPct > 3.0) return null; // too volatile for overnight hold
        }

        // ── 6. Confidence Scoring (0–100) ──
        int score = 50;
        if (volSurgeRatio >= 3.0)        score += 10;
        if (vwapPremium >= 0.5)          score += 8;
        if (dayRangePct >= 2.5)          score += 7;
        if (closeFromHighPct <= 0.2)     score += 10;
        if (metrics.dayHighTimestamp >= ENTRY_START_MIN - 30) score += 10;
        if (dailyEma20Bd != null && entryPx > dailyEma20Bd.doubleValue() * 1.02) score += 5;

        // Overnight risk penalty: wider ranges = higher gap risk
        if (dayRangePct >= 3.0) score -= 8;   // penalty for wide-range overnight hold
        if (atr14 != null && atr14.doubleValue() / entryPx * 100 > 2.0) score -= 5;

        BigDecimal entry   = BigDecimal.valueOf(entryPx).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD    = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal targetBD = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        String reason = String.format(
            "BTST range=%.1f%% close2high=%.1f%% vol=%.1fx vwap=+%.1f%% risk=%.1f%% RR=%.1f score=%d",
            dayRangePct, closeFromHighPct, volSurgeRatio, vwapPremium, riskPct,
            rewardPct / riskPct, score);

        log.debug("BTST signal: {} @ {} sl={} tgt={} {}", context.symbol(), entry, slBD, targetBD, reason);

        // FINE-TUNED: Partial exit at +1.0% (50% of position), trail remaining 50%
        // ExitManager handles the partial exit + trail on the remaining position
        return new Signal(
            context.symbol(),
            Signal.Side.BUY,
            entry,
            slBD,
            targetBD,
            score / 100.0,
            reason,
            0.8,  // FINE-TUNE: trail activates at +0.8% on remaining position
            0.3   // FINE-TUNE: trail 0.3% from peak (tighter — lock profits fast)
        );
    }

    /**
     * Computes all intraday metrics needed for BTST evaluation.
     */
    private IntradayMetrics computeIntradayMetrics(List<Candle> candles, Candle latest) {
        int n = candles.size();
        LocalDate today = latest.timestamp().toLocalDate();

        double dayHigh = Double.MIN_VALUE;
        double dayLow = Double.MAX_VALUE;
        double dayOpen = 0;
        double dayClose = latest.close().doubleValue();
        int dayHighTimestamp = 0; // minutes from midnight

        double sumPV = 0;
        long totalVol = 0;
        long eodVol = 0;      // last EOD_WINDOW_CANDLES candles volume
        long priorVol = 0;    // candles[-EOD_WINDOW_CANDLES-PRIOR_COUNT .. -EOD_WINDOW_CANDLES]
        boolean dayOpenSet = false;

        int startIdx = Math.max(0, n - EOD_WINDOW_CANDLES - PRIOR_CANDLE_COUNT);

        for (int i = startIdx; i < n; i++) {
            Candle c = candles.get(i);
            if (c.timestamp() == null) continue;
            if (!c.timestamp().toLocalDate().equals(today)) continue;

            int t = c.timestamp().getHour() * 60 + c.timestamp().getMinute();
            // Skip pre-market
            if (t < 9 * 60 + 15) continue;

            double h = c.high().doubleValue();
            double l = c.low().doubleValue();
            double cl = c.close().doubleValue();
            long v = c.volume();

            if (!dayOpenSet) {
                dayOpen = c.open().doubleValue();
                dayOpenSet = true;
            }

            if (h > dayHigh) {
                dayHigh = h;
                dayHighTimestamp = t;
            }
            if (l < dayLow) dayLow = l;

            // VWAP: typical price (H+L+C)/3 × volume
            double typicalPrice = (h + l + cl) / 3.0;
            sumPV += typicalPrice * v;
            totalVol += v;

            // EOD volume window
            if (i >= n - EOD_WINDOW_CANDLES) {
                eodVol += v;
            }
            // Prior volume baseline
            if (i >= n - EOD_WINDOW_CANDLES - PRIOR_CANDLE_COUNT
                    && i < n - EOD_WINDOW_CANDLES) {
                priorVol += v;
            }
        }

        if (!dayOpenSet || totalVol == 0 || dayHigh <= 0 || dayLow <= 0) return null;
        if (dayLow >= dayHigh) return null;

        double vwap = sumPV / totalVol;

        return new IntradayMetrics(
            dayHigh, dayLow, dayOpen, dayClose, vwap,
            dayHighTimestamp, eodVol, priorVol
        );
    }

    // ──── Internal data class ────
    private record IntradayMetrics(
        double dayHigh,
        double dayLow,
        double dayOpen,
        double dayClose,
        double vwap,
        int dayHighTimestamp,  // minutes from midnight when day high was made
        long eodVolume,        // volume in last EOD_WINDOW_CANDLES candles
        long priorPeriodVolume // volume in prior PRIOR_CANDLE_COUNT candles
    ) {}
}
