package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Bounce Strategy — high-probability entries only.
 *
 * Edge: Price pulls back TO intraday VWAP, holds it as support, then bounces.
 * This gives a natural tight SL (just below VWAP) and clear invalidation level.
 *
 * All 6 conditions must hold for a LONG:
 *   1. Time gate: IST 9:15–11:30 only (VWAP is most predictive in first 2 hours)
 *   2. VWAP bounce: previous candle ≤ VWAP, current close > VWAP (support confirmed)
 *   3. Bullish body: close > open AND body ≥ 40% of candle range (real conviction)
 *   4. Volume ≥ 2× 10-period average (smart money entering, not noise)
 *   5. RSI 45–62: upward momentum, not overbought
 *   6. Close > previous close: short-term direction confirmed
 */
@Slf4j
@Component
public class VwapTripleConfirmationStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "VWAP_TRIPLE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        Candle latest = context.getLatestCandle();
        Candle prev = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        List<Candle> candles = context.candles();
        if (candles.size() < 15) return null;

        BigDecimal close = latest.close();
        BigDecimal vwap = context.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        // 1. Time gate: IST 9:15–11:30 only
        Integer istHour = context.extra("istHour", Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null) {
            // Before 9:15 or after 11:30 IST
            boolean tooEarly = istHour < 9 || (istHour == 9 && istMinute != null && istMinute < 15);
            boolean tooLate = istHour > 11 || (istHour == 11 && istMinute != null && istMinute > 30);
            if (tooEarly || tooLate) {
                log.debug("VWAP Bounce: outside time gate {}:{}", istHour, istMinute);
                return null;
            }
        }

        // 2. VWAP bounce: previous candle must have been AT or BELOW VWAP
        //    (price touched VWAP as support and is now bouncing above it)
        if (prev.close().compareTo(vwap) > 0) {
            log.debug("VWAP Bounce: prev close {} > VWAP {} — not a bounce setup", prev.close(), vwap);
            return null;
        }
        // Current close must be above VWAP (confirmed bounce)
        if (close.compareTo(vwap) <= 0) {
            log.debug("VWAP Bounce: close {} still <= VWAP {}", close, vwap);
            return null;
        }

        // 3. Bullish candle with real body (not a doji or tiny pin)
        BigDecimal range = latest.high().subtract(latest.low());
        BigDecimal body = close.subtract(latest.open()).abs();
        if (close.compareTo(latest.open()) <= 0) {
            log.debug("VWAP Bounce: candle is bearish");
            return null;
        }
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bodyPct = body.divide(range, 4, RoundingMode.HALF_UP);
            if (bodyPct.doubleValue() < 0.40) {
                log.debug("VWAP Bounce: weak candle body {}% of range", bodyPct.multiply(BigDecimal.valueOf(100)).toPlainString());
                return null;
            }
        }

        // 4. Volume ≥ 2× 10-period average (conviction required)
        int n = candles.size();
        long volSum = candles.subList(Math.max(0, n - 10), n)
                .stream().mapToLong(Candle::volume).sum();
        long avgVol = volSum / Math.min(10, n);
        if (avgVol == 0 || latest.volume() < avgVol * 2.0) {
            log.debug("VWAP Bounce: volume {} < 2x avg {}", latest.volume(), avgVol);
            return null;
        }

        // 5. RSI 45–62: trending up without being overbought
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi == null || rsi.doubleValue() < 45 || rsi.doubleValue() > 62) {
            log.debug("VWAP Bounce: RSI {} outside [45,62]", rsi);
            return null;
        }

        // 6. Momentum: close > previous close
        if (close.compareTo(prev.close()) <= 0) {
            log.debug("VWAP Bounce: close {} <= prev close {}", close, prev.close());
            return null;
        }

        // SL just below VWAP — natural invalidation level
        // (strategy params control the exact %; default 0.2% gives VWAP as floor)
        BigDecimal sl = params.getStopLossPrice(vwap, Signal.Side.BUY); // SL from VWAP, not entry
        BigDecimal target = params.getTargetPrice(close, Signal.Side.BUY);

        return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                0.85,
                "VWAP Bounce LONG @" + close.setScale(2, RoundingMode.HALF_UP)
                        + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                        + " rsi=" + rsi.setScale(1, RoundingMode.HALF_UP)
                        + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol));
    }
}
