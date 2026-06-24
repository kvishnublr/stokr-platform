package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Trade Book Imbalance Strategy (Strategy #2).
 * Detects sustained institutional buying via 3-minute order imbalance and price action.
 *
 * Conditions (all must be true for LONG):
 *   1. Order flow: buyer/seller ratio > 2.0 (from latest tick, proxies sustained interest)
 *   2. Price rising in all 3 candles (close[i] > close[i-1])
 *   3. All 3 candles are bullish (close > open) — sustained buying each minute
 *   4. Volume increasing across the 3 candles (c0.vol <= c1.vol <= c2.vol) — growing participation
 *   5. Latest volume > 1.5 × SMA(volume, 10) — above-average activity
 *   6. ATR14 > 0.35% — enough volatility for a trade
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
        if (n < 3) return null;

        Candle c0 = candles.get(n - 3);
        Candle c1 = candles.get(n - 2);
        Candle c2 = candles.get(n - 1);

        // 1. Order flow: buyer/seller ratio > 2.0 (from Chartink tick data)
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

        // 2. Price rising in all 3 candles
        if (c2.close().compareTo(c1.close()) <= 0 || c1.close().compareTo(c0.close()) <= 0) {
            log.debug("TBI: price not rising for 3 candles {} {} {}",
                    c0.close(), c1.close(), c2.close());
            return null;
        }

        // 3. All 3 candles bullish — sustained buying each minute
        if (c2.close().compareTo(c2.open()) <= 0 ||
            c1.close().compareTo(c1.open()) <= 0 ||
            c0.close().compareTo(c0.open()) <= 0) {
            log.debug("TBI: not all 3 candles are bullish");
            return null;
        }

        // 4. Volume increasing across 3 candles — growing participation
        if (c2.volume() < c1.volume() || c1.volume() < c0.volume()) {
            log.debug("TBI: volume not increasing across 3 candles: {} < {} < {}",
                    c0.volume(), c1.volume(), c2.volume());
            return null;
        }

        // 5. Latest volume > 1.5 × SMA(volume, 10)
        long avgVol = candles.subList(n - 10, n)
                .stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || c2.volume() < avgVol * 1.5) {
            log.debug("TBI: volume {} < 1.5x avg {}", c2.volume(), avgVol);
            return null;
        }

        // 6. ATR14 > 0.35%
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
