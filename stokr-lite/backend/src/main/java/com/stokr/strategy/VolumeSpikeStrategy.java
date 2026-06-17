package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Volume Spike / Momentum Ignition Strategy (Strategy G).
 * Enters when volume surges above average with price momentum.
 */
@Slf4j
@Component
public class VolumeSpikeStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "VOLUME_SPIKE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        if (candles.size() < 10) return null;

        Candle latest = context.getLatestCandle();
        Candle previous = context.getPreviousCandle();
        if (latest == null || previous == null) return null;

        // Volume spike: current volume > 2x average of last 10 candles
        long avgVolume = candles.subList(candles.size() - 10, candles.size())
                .stream().mapToLong(Candle::volume).sum() / 10;
        if (latest.volume() < avgVolume * 2) return null;

        BigDecimal close = latest.close();
        BigDecimal prevClose = previous.close();

        // Price must move in direction of volume
        boolean bullish = close.compareTo(prevClose) > 0;
        boolean bearish = close.compareTo(prevClose) < 0;

        if (!bullish && !bearish) return null;

        // Momentum check: move > 0.3%
        BigDecimal changePct = close.subtract(prevClose)
                .divide(prevClose, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (changePct.abs().doubleValue() < 0.3) return null;

        if (bullish) {
            BigDecimal sl = params.getStopLossPrice(close, Signal.Side.BUY);
            BigDecimal target = params.getTargetPrice(close, Signal.Side.BUY);
            return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                    0.7, "Volume spike ignition UP vol=" + latest.volume() + " avg=" + avgVolume);
        } else {
            BigDecimal sl = params.getStopLossPrice(close, Signal.Side.SELL);
            BigDecimal target = params.getTargetPrice(close, Signal.Side.SELL);
            return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target,
                    0.7, "Volume spike ignition DOWN vol=" + latest.volume() + " avg=" + avgVolume);
        }
    }
}
