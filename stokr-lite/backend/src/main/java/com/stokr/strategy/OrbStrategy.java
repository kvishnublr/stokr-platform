package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Opening Range Breakout Strategy.
 * Buys when price breaks above the opening range high (first N minutes).
 * Sells when price breaks below the opening range low.
 */
@Slf4j
@Component
public class OrbStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "ORB";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        if (candles.size() < params.orbPeriodMinutes()) {
            return null; // Not enough data for opening range
        }

        // Calculate opening range (first N candles of the day)
        List<Candle> openingCandles = candles.subList(0, params.orbPeriodMinutes());
        BigDecimal rangeHigh = openingCandles.stream()
                .map(Candle::high)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        BigDecimal rangeLow = openingCandles.stream()
                .map(Candle::low)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        Candle latest = context.getLatestCandle();
        if (latest == null) return null;

        BigDecimal closePrice = latest.close();

        // Check for breakout above range high
        if (closePrice.compareTo(rangeHigh) > 0) {
            // Volume confirmation
            long avgVolume = openingCandles.stream()
                    .mapToLong(Candle::volume)
                    .sum() / openingCandles.size();
            if (latest.volume() < avgVolume * params.volumeMultiplier()) {
                log.debug("ORB: Breakout above {} but volume too low for {}", rangeHigh, context.symbol());
                return null;
            }

            BigDecimal entry = closePrice;
            BigDecimal sl = params.getStopLossPrice(entry, Signal.Side.BUY);
            BigDecimal target = params.getTargetPrice(entry, Signal.Side.BUY);

            return new Signal(context.symbol(), Signal.Side.BUY, entry, sl, target,
                    0.7, "ORB breakout above " + rangeHigh.setScale(2, RoundingMode.HALF_UP));
        }

        // Check for breakdown below range low
        if (closePrice.compareTo(rangeLow) < 0) {
            BigDecimal entry = closePrice;
            BigDecimal sl = params.getStopLossPrice(entry, Signal.Side.SELL);
            BigDecimal target = params.getTargetPrice(entry, Signal.Side.SELL);

            return new Signal(context.symbol(), Signal.Side.SELL, entry, sl, target,
                    0.7, "ORB breakdown below " + rangeLow.setScale(2, RoundingMode.HALF_UP));
        }

        return null;
    }
}
