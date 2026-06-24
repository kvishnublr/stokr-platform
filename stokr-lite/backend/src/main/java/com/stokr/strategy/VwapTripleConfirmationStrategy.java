package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Triple Confirmation Strategy (Strategy #1).
 * Conditions (all must be true for LONG):
 *   1. price > VWAP * 1.002
 *   2. volume > 1.5 × sma(volume, 10)
 *   3. RSI(14) between 40 and 70
 *   4. buyer/seller qty ratio > 1.3
 *   5. ATR_15min > 0.35%
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
        if (latest == null) return null;

        List<Candle> candles = context.candles();
        if (candles.size() < 10) return null;

        BigDecimal close = latest.close();
        BigDecimal vwap = context.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        // 1. Price > VWAP * 1.002
        BigDecimal vwapThreshold = vwap.multiply(BigDecimal.valueOf(1.002));
        if (close.compareTo(vwapThreshold) <= 0) {
            log.debug("VWAP Triple: close {} <= VWAP*1.002 {}", close, vwapThreshold);
            return null;
        }

        // 2. Volume > 1.5 × SMA(volume, 10)
        long avgVol = candles.subList(candles.size() - 10, candles.size())
                .stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || latest.volume() < avgVol * 1.5) {
            log.debug("VWAP Triple: volume {} < 1.5x avg {}", latest.volume(), avgVol);
            return null;
        }

        // 3. RSI(14) between 40 and 70
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi == null || rsi.doubleValue() < 40 || rsi.doubleValue() > 70) {
            log.debug("VWAP Triple: RSI {} out of range [40,70]", rsi);
            return null;
        }

        // 4. Buyer/seller ratio > 1.3
        Long buyerQty = context.extra("buyerQty", Long.class);
        Long sellerQty = context.extra("sellerQty", Long.class);
        if (buyerQty == null || sellerQty == null || sellerQty == 0) {
            log.debug("VWAP Triple: missing buyer/seller qty");
            return null;
        }
        double ratio = buyerQty / (double) sellerQty;
        if (ratio <= 1.3) {
            log.debug("VWAP Triple: buyer/seller ratio {} <= 1.3", ratio);
            return null;
        }

        // 5. ATR% > threshold (0.35% for daily, or overridden via extras for shorter timeframes)
        BigDecimal atr = context.indicators() != null ? context.indicators().get("ATR14") : null;
        if (atr == null) atr = context.extra("atr14", BigDecimal.class);
        if (atr == null) {
            log.debug("VWAP Triple: ATR not available");
            return null;
        }
        BigDecimal atrPct = atr.divide(close, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        BigDecimal atrThreshold = context.extra("atrThresholdPct", BigDecimal.class);
        double threshold = atrThreshold != null ? atrThreshold.doubleValue() : 0.35;
        if (atrPct.doubleValue() <= threshold) {
            log.debug("VWAP Triple: ATR% {} <= {}%", atrPct, threshold);
            return null;
        }

        BigDecimal sl = params.getStopLossPrice(close, Signal.Side.BUY);
        BigDecimal target = params.getTargetPrice(close, Signal.Side.BUY);
        return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                0.75, "VWAP Triple LONG vw=" + vwap.setScale(2, RoundingMode.HALF_UP)
                        + " rsi=" + rsi.setScale(1, RoundingMode.HALF_UP)
                        + " ratio=" + String.format("%.2f", ratio));
    }
}
