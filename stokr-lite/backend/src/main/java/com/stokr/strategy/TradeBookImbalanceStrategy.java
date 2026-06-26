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

        // 1. Order flow: buyer/seller ratio > 2.0
        // Live mode: use real order book qty. Backtest: proxy via CLV × volume across 3 candles.
        // CLV = (2×close - high - low) / (high - low) measures close position within the bar.
        Long buyerQty = context.extra("buyerQty", Long.class);
        Long sellerQty = context.extra("sellerQty", Long.class);
        double ratio;
        if (buyerQty != null && sellerQty != null && sellerQty > 0) {
            ratio = buyerQty / (double) sellerQty;
        } else {
            double buyPressure = 0, sellPressure = 0;
            for (Candle c : List.of(c0, c1, c2)) {
                double range = c.high().subtract(c.low()).doubleValue();
                if (range <= 0) continue;
                double clv = c.close().subtract(c.low())
                        .subtract(c.high().subtract(c.close()))
                        .doubleValue() / range;
                double vol = c.volume();
                buyPressure  += vol * Math.max(0, clv);
                sellPressure += vol * Math.max(0, -clv);
            }
            ratio = (sellPressure == 0) ? 3.0 : buyPressure / sellPressure;
        }
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

        // 3. All 3 candles bullish (close > open) with decent body (≥30% of range — no doji)
        for (Candle c : List.of(c0, c1, c2)) {
            double body  = c.close().subtract(c.open()).doubleValue();
            double range = c.high().subtract(c.low()).doubleValue();
            if (body <= 0) {
                log.debug("TBI: candle not bullish");
                return null;
            }
            if (range > 0 && body / range < 0.30) {
                log.debug("TBI: wick-heavy candle body/range={}", body / range);
                return null;
            }
        }

        // 4. Volume increasing across 3 candles — growing participation
        if (c2.volume() < c1.volume() || c1.volume() < c0.volume()) {
            log.debug("TBI: volume not increasing {} {} {}", c0.volume(), c1.volume(), c2.volume());
            return null;
        }

        // 5. Latest volume > 1.5 × SMA(volume, 10) — above-average activity
        long avgVol = candles.subList(n - 10, n)
                .stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || c2.volume() < avgVol * 1.5) {
            log.debug("TBI: volume {} < 1.5x avg {}", c2.volume(), avgVol);
            return null;
        }

        // 6. ATR14 volatility check.
        // Live mode: ATR comes from Chartink daily feed (~1-3% scale, threshold 0.35%).
        // Backtest mode: ATR is computed from 1-min bars (~0.05-0.15% scale, threshold 0.05%).
        BigDecimal atr = context.indicators() != null ? context.indicators().get("ATR14") : null;
        if (atr == null) atr = context.extra("atr14", BigDecimal.class);
        if (atr == null) {
            log.debug("TBI: ATR not available");
            return null;
        }
        BigDecimal atrPct = atr.divide(c2.close(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        boolean isLiveMode = (buyerQty != null && sellerQty != null);
        double atrThreshold = isLiveMode ? 0.35 : 0.05;
        if (atrPct.doubleValue() <= atrThreshold) {
            log.debug("TBI: ATR% {} <= {}% (live={})", atrPct, atrThreshold, isLiveMode);
            return null;
        }

        BigDecimal sl = params.getStopLossPrice(c2.close(), Signal.Side.BUY);
        BigDecimal target = params.getTargetPrice(c2.close(), Signal.Side.BUY);
        return new Signal(context.symbol(), Signal.Side.BUY, c2.close(), sl, target,
                0.78, "TBI LONG ratio=" + String.format("%.2f", ratio)
                        + " vol=" + c2.volume() + "/" + avgVol);
    }
}
