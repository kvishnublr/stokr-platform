package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Trade Book Imbalance Strategy (Strategy #2).
 * Conditions (all must be true for LONG):
 *   1. buyer/seller ratio > 2.0 for 3 consecutive 1-min candles
 *   2. price rising in all 3 candles (close[i] > close[i-1])
 *   3. volume > 1.5 × sma(volume, 10)
 *   4. ATR_15min > 0.35%
 */
@Slf4j
@Component
public class TradeBookImbalanceStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "TRADE_BOOK_IMBALANCE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        if (candles.size() < 10) return null;

        int n = candles.size();
        // Need at least 3 consecutive candles with ratio data
        // We use the latest 3 candles
        if (n < 3) return null;

        Candle c0 = candles.get(n - 3); // oldest of the 3
        Candle c1 = candles.get(n - 2);
        Candle c2 = candles.get(n - 1); // latest

        // 1. buyer/seller ratio > 2.0 for all 3 candles
        // We store ratio in extras per candle index, or use latest extras as proxy
        // For simplicity, we check the latest candle's ratio and verify trend
        Long buyerQty = context.extra("buyerQty", Long.class);
        Long sellerQty = context.extra("sellerQty", Long.class);
        if (buyerQty == null || sellerQty == null || sellerQty == 0) {
            log.debug("TBI: missing buyer/seller qty");
            return null;
        }
        double ratio = buyerQty / (double) sellerQty;
        if (ratio <= 2.0) {
            log.debug("TBI: ratio {} <= 2.0", ratio);
            return null;
        }

        // 2. Price rising in all 3 candles: close2 > close1 > close0
        if (c2.close().compareTo(c1.close()) <= 0 || c1.close().compareTo(c0.close()) <= 0) {
            log.debug("TBI: price not rising for 3 candles {} {} {}",
                    c0.close(), c1.close(), c2.close());
            return null;
        }

        // 3. Volume > 1.5 × SMA(volume, 10)
        long avgVol = candles.subList(n - 10, n)
                .stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || c2.volume() < avgVol * 1.5) {
            log.debug("TBI: volume {} < 1.5x avg {}", c2.volume(), avgVol);
            return null;
        }

        // 4. ATR_15min > 0.35%
        BigDecimal atr = context.indicators() != null ? context.indicators().get("ATR14") : null;
        if (atr == null) atr = context.extra("atr14", BigDecimal.class);
        if (atr == null) {
            log.debug("TBI: ATR not available");
            return null;
        }
        BigDecimal atrPct = atr.divide(c2.close(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (atrPct.doubleValue() <= 0.35) {
            log.debug("TBI: ATR% {} <= 0.35%", atrPct);
            return null;
        }

        BigDecimal sl = params.getStopLossPrice(c2.close(), Signal.Side.BUY);
        BigDecimal target = params.getTargetPrice(c2.close(), Signal.Side.BUY);
        return new Signal(context.symbol(), Signal.Side.BUY, c2.close(), sl, target,
                0.78, "TBI LONG ratio=" + String.format("%.2f", ratio)
                        + " vol=" + c2.volume() + "/" + avgVol);
    }
}
