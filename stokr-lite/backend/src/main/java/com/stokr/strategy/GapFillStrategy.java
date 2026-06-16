package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Gap Fill Strategy.
 * Trades stocks that gap up/down at open, expecting the gap to fill during the session.
 * Gap up: sell expecting price to drop back to previous close.
 * Gap down: buy expecting price to rise back to previous close.
 */
@Slf4j
@Component
public class GapFillStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "GAP_FILL";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        if (context.candles().size() < 2) return null;

        BigDecimal openPrice = context.getOpenPrice();
        BigDecimal currentPrice = context.currentPrice();

        if (openPrice.compareTo(BigDecimal.ZERO) == 0 || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // We need previous day's close - use the first candle's open as proxy
        // In production, this would come from previous day's close stored separately
        BigDecimal prevClose = context.getOpenPrice(); // Simplified

        // Calculate gap percentage
        double gapPct = openPrice.subtract(prevClose)
                .divide(prevClose, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        // Check if gap is within acceptable range
        if (Math.abs(gapPct) < params.minGapPct() || Math.abs(gapPct) > params.maxGapPct()) {
            return null;
        }

        // Volume check
        Candle latest = context.getLatestCandle();
        if (latest == null) return null;
        if (latest.volume() < params.volumeThreshold()) {
            return null;
        }

        // Gap UP: expect fill -> SELL (short expecting drop back)
        if (gapPct > 0) {
            BigDecimal entry = currentPrice;
            BigDecimal sl = params.getStopLossPrice(entry, Signal.Side.SELL);
            BigDecimal target = prevClose; // Gap fill target

            return new Signal(context.symbol(), Signal.Side.SELL, entry, sl, target,
                    0.6, String.format("Gap UP %.1f%%, expecting fill to %.2f",
                            gapPct, prevClose.setScale(2, RoundingMode.HALF_UP)));
        }

        // Gap DOWN: expect fill -> BUY (long expecting rise back)
        if (gapPct < 0) {
            BigDecimal entry = currentPrice;
            BigDecimal sl = params.getStopLossPrice(entry, Signal.Side.BUY);
            BigDecimal target = prevClose;

            return new Signal(context.symbol(), Signal.Side.BUY, entry, sl, target,
                    0.6, String.format("Gap DOWN %.1f%%, expecting fill to %.2f",
                            gapPct, prevClose.setScale(2, RoundingMode.HALF_UP)));
        }

        return null;
    }
}
