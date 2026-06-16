package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * VWAP Bounce Strategy.
 * Enters long when price bounces off VWAP from below (support).
 * Enters short when price rejects from VWAP from above (resistance).
 */
@Slf4j
@Component
public class VwapBounceStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "VWAP_BOUNCE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        if (context.vwap() == null || context.vwap().compareTo(BigDecimal.ZERO) == 0) {
            return null; // VWAP not available
        }

        Candle latest = context.getLatestCandle();
        Candle previous = context.getPreviousCandle();
        if (latest == null || previous == null) return null;

        BigDecimal vwap = context.vwap();
        BigDecimal closePrice = latest.close();
        BigDecimal prevClose = previous.close();
        BigDecimal deviation = vwap.multiply(BigDecimal.valueOf(params.vwapDeviationPct() / 100.0));

        // Bullish bounce: previous candle below VWAP, current candle closes above VWAP
        if (prevClose.compareTo(vwap) < 0 && closePrice.compareTo(vwap) > 0) {
            // Check if the cross happened near VWAP (within deviation)
            if (closePrice.subtract(vwap).abs().compareTo(deviation) <= 0) {
                BigDecimal entry = closePrice;
                BigDecimal sl = params.getStopLossPrice(entry, Signal.Side.BUY);
                BigDecimal target = params.getTargetPrice(entry, Signal.Side.BUY);

                return new Signal(context.symbol(), Signal.Side.BUY, entry, sl, target,
                        0.65, "VWAP bullish bounce at " + vwap.setScale(2, RoundingMode.HALF_UP));
            }
        }

        // Bearish rejection: previous candle above VWAP, current candle closes below VWAP
        if (prevClose.compareTo(vwap) > 0 && closePrice.compareTo(vwap) < 0) {
            if (vwap.subtract(closePrice).abs().compareTo(deviation) <= 0) {
                BigDecimal entry = closePrice;
                BigDecimal sl = params.getStopLossPrice(entry, Signal.Side.SELL);
                BigDecimal target = params.getTargetPrice(entry, Signal.Side.SELL);

                return new Signal(context.symbol(), Signal.Side.SELL, entry, sl, target,
                        0.65, "VWAP bearish rejection at " + vwap.setScale(2, RoundingMode.HALF_UP));
            }
        }

        return null;
    }
}
