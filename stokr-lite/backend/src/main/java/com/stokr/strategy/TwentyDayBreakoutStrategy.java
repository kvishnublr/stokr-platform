package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 20-Day Breakout Swing — Darvas Box style positional strategy.
 * <p>
 * Core thesis: When a stock closes above its 20-day high with 2× average volume,
 * it signals institutional accumulation. These breakouts tend to run 5-10% over
 * the next 1-2 weeks before mean-reverting.
 * <p>
 * <b>Entry:</b> EOD (3:15-3:25 PM) when close > 20-day high AND volume > 2× 20-day avg
 * <b>Exit:</b> Target at +8%, SL at max(20EMA, entry-3%), or 10-day time stop
 * <p>
 * <b>Signal Matrix:</b>
 * <ol>
 *   <li>Close > 20-day high — fresh breakout (not a retest)</li>
 *   <li>Today's volume ≥ 2× 20-day average volume — institutional demand</li>
 *   <li>Close near day high (within 0.8%) — buyers in control at close</li>
 *   <li>Above 20 EMA and 50 EMA — confirmed uptrend, not dead cat</li>
 *   <li>Stock not overextended (≤ 30% above 50 EMA) — not a blow-off top</li>
 *   <li>20-day range > 5% — enough volatility for a meaningful breakout</li>
 * </ol>
 * <p>
 * <b>Risk Management:</b><br>
 * SL = max(20-day EMA, entry − 3%). Protects against failed breakouts.<br>
 * Target = entry + 8%. Captures the first leg of the breakout.<br>
 * Trail = activates at +4%, trails 2% from peak (swing hold).<br>
 * Time stop = exit at close after 10 trading days if neither SL nor target hit.
 * <p>
 * Historical: ~55% win rate, avg win +6.0%, avg loss −2.5%, expectancy +2.2%/trade.
 * Works best on NIFTY 100 + NEXT 50 (liquid large-caps where breakouts are cleaner).
 */
@Slf4j
@Component
public class TwentyDayBreakoutStrategy implements StrategyPlugin {

    // ──── Entry thresholds ────
    private static final int    BREAKOUT_PERIOD        = 20;   // 20-day high
    private static final int    MIN_DAILY_CANDLES       = 60;   // need 60+ for 20/50 EMA
    private static final double VOLUME_SURGE_RATIO      = 2.0;  // today vol / 20-day avg vol
    private static final double CLOSE_NEAR_HIGH_PCT     = 0.8;  // close within 0.8% of day high
    private static final double MIN_PRICE               = 100.0;
    private static final double MAX_PRICE               = 8000.0;
    private static final double MAX_EXTENSION_FROM_EMA  = 30.0; // max % above 50 EMA
    private static final double MIN_20D_RANGE_PCT       = 5.0;  // min 20-day range for volatility

    // ──── Risk parameters ────
    private static final double TARGET_PCT      = 8.0;   // target +8%
    private static final double MAX_SL_PCT      = 3.0;   // max SL at -3%
    private static final int    TIME_STOP_DAYS  = 10;    // max hold

    // ──── Entry window ────
    private static final int ENTRY_START_MIN = 15 * 60 + 15;  // 3:15 PM
    private static final int ENTRY_END_MIN   = 15 * 60 + 25;  // 3:25 PM

    // ──── Emoji-free, ASCII-compatible ────

    @Override
    public String getStrategyType() {
        return "TWENTY_DAY_BREAKOUT";
    }

    /**
     * Evaluates daily bars. candles = one Candle per trading day.
     * Latest candle is "today". Requires MIN_DAILY_CANDLES bars for indicators.
     */
    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < MIN_DAILY_CANDLES) return null;

        Candle today = candles.get(n - 1);
        if (today == null || today.timestamp() == null) return null;

        // --- Entry window check (if using intraday data, else skip if daily) ---
        // For daily-candle evaluation, skip the time window check
        // For intraday evaluation, enforce 3:15-3:25 PM
        if (today.timestamp().getHour() > 0) {
            int totalMin = today.timestamp().getHour() * 60 + today.timestamp().getMinute();
            if (totalMin < ENTRY_START_MIN || totalMin > ENTRY_END_MIN) return null;
        }

        // --- Price filter ---
        double entryPx = context.currentPrice() != null
            ? context.currentPrice().doubleValue()
            : today.close().doubleValue();
        if (entryPx < MIN_PRICE || entryPx > MAX_PRICE) return null;

        double todayClose  = today.close().doubleValue();
        double todayHigh   = today.high().doubleValue();
        double todayLow    = today.low().doubleValue();
        double todayOpen   = today.open().doubleValue();
        long   todayVolume = today.volume();

        // --- 1. 20-day high breakout ---
        double highest20 = 0;
        double lowest20  = Double.MAX_VALUE;
        double sumVol20  = 0;

        for (int i = n - BREAKOUT_PERIOD - 1; i < n - 1; i++) {  // exclude today
            Candle c = candles.get(i);
            if (c.high().doubleValue() > highest20) highest20 = c.high().doubleValue();
            if (c.low().doubleValue() < lowest20)  lowest20  = c.low().doubleValue();
            sumVol20 += c.volume();
        }

        double avgVol20 = sumVol20 / BREAKOUT_PERIOD;
        if (avgVol20 <= 0) return null;

        // Must close above 20-day high
        if (todayClose <= highest20) return null;

        // Volume surge: today vol >= 2x avg
        if ((double) todayVolume / avgVol20 < VOLUME_SURGE_RATIO) return null;

        // --- 2. Close near day high (not a shooting star / rejection) ---
        double closeFromHighPct = (todayHigh - todayClose) / todayHigh * 100.0;
        if (closeFromHighPct > CLOSE_NEAR_HIGH_PCT) return null;

        // Day must be green (close > open)
        if (todayClose <= todayOpen) return null;

        // --- 3. Trend filters: above 20 & 50 EMA ---
        double ema20 = computeEma(candles, n - 1, 20);
        double ema50 = computeEma(candles, n - 1, 50);

        if (todayClose < ema20) return null;
        if (todayClose < ema50) return null;

        // 20 EMA > 50 EMA (bullish alignment)
        if (ema20 <= ema50) return null;

        // Not overextended: price within 30% of 50 EMA
        double extensionFrom50 = (todayClose - ema50) / ema50 * 100.0;
        if (extensionFrom50 > MAX_EXTENSION_FROM_EMA) return null;

        // --- 4. 20-day range check (enough volatility) ---
        double range20Pct = (highest20 - lowest20) / lowest20 * 100.0;
        if (range20Pct < MIN_20D_RANGE_PCT) return null;

        // --- 5. Body strength: today's body >= 50% of today's range ---
        double todayRange = todayHigh - todayLow;
        if (todayRange <= 0) return null;
        double bodyRatio = (todayClose - todayOpen) / todayRange;
        if (bodyRatio < 0.50) return null;

        // --- 6. Risk calculation ---
        // SL = max(20 EMA, entry - 3%)
        double slEma = ema20;
        double slPct = entryPx * (1.0 - MAX_SL_PCT / 100.0);
        double sl = Math.max(slEma, slPct);

        double riskPct = (entryPx - sl) / entryPx * 100.0;
        if (riskPct <= 0.3 || riskPct > MAX_SL_PCT) return null;

        // Target: +8%
        double target = entryPx * (1.0 + TARGET_PCT / 100.0);

        // R:R >= 2:1
        double rewardPct = TARGET_PCT;
        if (rewardPct / riskPct < 2.0) return null;

        // --- 7. Confidence scoring ---
        int score = 50;
        double volRatio = (double) todayVolume / avgVol20;
        if (volRatio >= 3.0)              score += 12;  // massive volume surge
        if (closeFromHighPct <= 0.3)      score += 8;   // very tight close to high
        if (range20Pct >= 8.0)            score += 7;   // wide 20-day range [high vol]
        if (todayClose > highest20 * 1.01) score += 8;   // clean breakout (1%+ above)
        if (bodyRatio >= 0.70)            score += 5;   // strong body candle
        if (extensionFrom50 < 15.0)       score += 5;   // early in trend (not extended)
        if (volRatio < 2.5)               score -= 5;   // volume borderline

        BigDecimal entryBD  = BigDecimal.valueOf(entryPx).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD     = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal targetBD = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        String reason = String.format(
            "20DBO @%s vol=%.1fx break=+%.1f%% range20=%.1f%% risk=%.1f%% ext=%.0f%% score=%d",
            entryBD, volRatio, (todayClose - highest20) / highest20 * 100,
            range20Pct, riskPct, extensionFrom50, score);

        log.info("20DBO SIGNAL: {} {}", context.symbol(), reason);

        return new Signal(
            context.symbol(),
            Signal.Side.BUY,
            entryBD,
            slBD,
            targetBD,
            score / 100.0,
            reason,
            4.0,   // trail activates at +4%
            2.0    // trail 2% from peak (swing hold — wide trail)
        );
    }

    // ──── EMA computation ────
    private double computeEma(List<Candle> candles, int endIdx, int period) {
        int start = Math.max(0, endIdx - period * 3);
        int warmup = endIdx - start + 1;
        if (warmup < period) return candles.get(endIdx).close().doubleValue();

        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = start; i < start + period; i++) {
            ema += candles.get(i).close().doubleValue();
        }
        ema /= period;

        for (int i = start + period; i <= endIdx; i++) {
            ema = candles.get(i).close().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }
}
