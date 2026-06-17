package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * ORB-V Breakout Strategy (Strategy #4).
 * Enhanced ORB with volume + trade book filters.
 * Conditions (all must be true for LONG):
 *   1. close > intraday 15-min high (ORB high)
 *   2. volume > 1.5 × sma(volume, 10)
 *   3. buyer/seller qty ratio > 1.3
 *   4. close > previous close
 */
@Slf4j
@Component
public class OrbVStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "ORB_V";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        if (candles.size() < 15) return null;

        int orbPeriod = params.orbPeriodMinutes() > 0 ? params.orbPeriodMinutes() : 15;
        if (candles.size() < orbPeriod) return null;

        // 1. Calculate opening range high (first N candles)
        List<Candle> opening = candles.subList(0, orbPeriod);
        BigDecimal orbHigh = opening.stream()
                .map(Candle::high)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        Candle latest = context.getLatestCandle();
        Candle prev = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();

        // Close must break above ORB high
        if (close.compareTo(orbHigh) <= 0) {
            log.debug("ORB-V: close {} <= ORB high {}", close, orbHigh);
            return null;
        }

        // 2. Volume > 1.5 × SMA(volume, 10)
        int n = candles.size();
        long avgVol = candles.subList(Math.max(0, n - 10), n)
                .stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || latest.volume() < avgVol * 1.5) {
            log.debug("ORB-V: volume {} < 1.5x avg {}", latest.volume(), avgVol);
            return null;
        }

        // 3. Buyer/seller ratio > 1.3
        Long buyerQty = context.extra("buyerQty", Long.class);
        Long sellerQty = context.extra("sellerQty", Long.class);
        if (buyerQty == null || sellerQty == null || sellerQty == 0) {
            log.debug("ORB-V: missing buyer/seller qty");
            return null;
        }
        double ratio = buyerQty / (double) sellerQty;
        if (ratio <= 1.3) {
            log.debug("ORB-V: ratio {} <= 1.3", ratio);
            return null;
        }

        // 4. Close > previous close
        if (close.compareTo(prev.close()) <= 0) {
            log.debug("ORB-V: close {} <= prev close {}", close, prev.close());
            return null;
        }

        BigDecimal sl = params.getStopLossPrice(close, Signal.Side.BUY);
        BigDecimal target = params.getTargetPrice(close, Signal.Side.BUY);
        return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                0.72, "ORB-V LONG break=" + orbHigh.setScale(2, RoundingMode.HALF_UP)
                        + " ratio=" + String.format("%.2f", ratio));
    }
}
