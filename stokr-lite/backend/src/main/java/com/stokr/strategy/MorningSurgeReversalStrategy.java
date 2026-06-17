package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Morning Surge Reversal Strategy (Strategy #5).
 * Mean-reversion SHORT after excessive morning surge.
 * Conditions (all must be true for SHORT):
 *   1. % change > 3.0 AND < 5.0 (from open or prev close)
 *   2. volume > 2.0 × sma(volume, 10)
 *   3. buyer/seller qty ratio < 0.67 (selling pressure building)
 *   4. RSI(14) > 70 (overbought)
 *   5. close > 1 day ago high (extended)
 */
@Slf4j
@Component
public class MorningSurgeReversalStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "MORNING_SURGE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        Candle latest = context.getLatestCandle();
        if (latest == null) return null;

        List<Candle> candles = context.candles();
        if (candles.size() < 10) return null;

        BigDecimal close = latest.close();
        BigDecimal open = context.getOpenPrice();
        if (open.compareTo(BigDecimal.ZERO) == 0) return null;

        // 1. % change from open between 3% and 5%
        BigDecimal changePct = close.subtract(open)
                .divide(open, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (changePct.doubleValue() <= 3.0 || changePct.doubleValue() >= 5.0) {
            log.debug("Morning Surge: change% {} not in (3,5)", changePct);
            return null;
        }

        // 2. Volume > 2.0 × SMA(volume, 10)
        int n = candles.size();
        long avgVol = candles.subList(n - 10, n)
                .stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || latest.volume() < avgVol * 2.0) {
            log.debug("Morning Surge: volume {} < 2.0x avg {}", latest.volume(), avgVol);
            return null;
        }

        // 3. Buyer/seller ratio < 0.67
        Long buyerQty = context.extra("buyerQty", Long.class);
        Long sellerQty = context.extra("sellerQty", Long.class);
        if (buyerQty == null || sellerQty == null || buyerQty == 0) {
            log.debug("Morning Surge: missing buyer/seller qty");
            return null;
        }
        double ratio = buyerQty / (double) sellerQty;
        if (ratio >= 0.67) {
            log.debug("Morning Surge: ratio {} >= 0.67", ratio);
            return null;
        }

        // 4. RSI(14) > 70
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi == null || rsi.doubleValue() <= 70) {
            log.debug("Morning Surge: RSI {} <= 70", rsi);
            return null;
        }

        // 5. close > 1 day ago high (prevClose as proxy for day high)
        BigDecimal prevClose = context.extra("prevClose", BigDecimal.class);
        if (prevClose == null && candles.size() > 1) {
            prevClose = candles.get(0).close(); // day open candle close as proxy
        }
        if (prevClose == null || close.compareTo(prevClose) <= 0) {
            log.debug("Morning Surge: close {} <= prevClose/dayHigh {}", close, prevClose);
            return null;
        }

        BigDecimal sl = params.getStopLossPrice(close, Signal.Side.SELL);
        BigDecimal target = params.getTargetPrice(close, Signal.Side.SELL);
        return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target,
                0.70, "Morning Surge SHORT change=" + changePct.setScale(2, RoundingMode.HALF_UP)
                        + "% rsi=" + rsi.setScale(1, RoundingMode.HALF_UP)
                        + " ratio=" + String.format("%.2f", ratio));
    }
}
