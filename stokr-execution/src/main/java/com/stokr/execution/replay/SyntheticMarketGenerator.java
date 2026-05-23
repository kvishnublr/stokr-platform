package com.stokr.execution.replay;

import com.stokr.marketdata.domain.MarketdataCandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Random;

/**
 * Generates synthetic market data for stress testing.
 * Uses configurable models: random walk, volatility clustering, trend + mean reversion, gaps/spikes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyntheticMarketGenerator {

    private final Random random = new Random();

    /**
     * Generate random walk prices.
     * Simplest model: each tick is a random step from previous price.
     */
    public BigDecimal randomWalk(BigDecimal currentPrice, BigDecimal volatility) {
        // Normal distribution: mean=0, std=volatility
        double change = random.nextGaussian() * volatility.doubleValue();
        BigDecimal newPrice = currentPrice.multiply(BigDecimal.valueOf(1.0 + change));
        return newPrice.setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Generate prices with volatility clustering.
     * High volatility periods tend to cluster (realistic market behavior).
     */
    public BigDecimal volatilityClusteringWalk(BigDecimal currentPrice, BigDecimal baseVolatility,
                                               java.util.concurrent.atomic.AtomicReference<Double> volatilityFactor) {
        double currentVol = volatilityFactor.get();

        // Decay volatility gradually
        double decayFactor = 0.95;
        double newVol = currentVol > baseVolatility.doubleValue()
                ? currentVol * decayFactor
                : baseVolatility.doubleValue();

        // Occasionally spike volatility
        if (random.nextDouble() < 0.02) {  // 2% chance
            newVol = Math.min(newVol * 3.0, 0.1);  // spike up to 3x, capped at 10%
        }

        volatilityFactor.set(newVol);

        double change = random.nextGaussian() * newVol;
        BigDecimal newPrice = currentPrice.multiply(BigDecimal.valueOf(1.0 + change));
        return newPrice.setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Generate prices with trend and mean reversion.
     * Markets tend to trend but eventually revert to mean.
     */
    public BigDecimal trendMeanReversion(BigDecimal currentPrice, BigDecimal meanPrice,
                                         java.util.concurrent.atomic.AtomicReference<Double> trendFactor) {
        double trend = trendFactor.get();

        // Mean reversion pulls price toward mean
        double meanReversion = 0.02;
        double deviationFromMean = (currentPrice.doubleValue() - meanPrice.doubleValue()) / meanPrice.doubleValue();
        double reversion = -deviationFromMean * meanReversion;

        // Random noise
        double noise = random.nextGaussian() * 0.01;

        // Update trend (random walk on trend)
        trend += random.nextGaussian() * 0.01;
        trend = Math.max(-0.05, Math.min(trend, 0.05));  // cap trend at ±5%
        trendFactor.set(trend);

        double change = trend + reversion + noise;
        BigDecimal newPrice = currentPrice.multiply(BigDecimal.valueOf(1.0 + change));
        return newPrice.setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Generate prices with gaps and spikes.
     * Simulates market gaps (open different from previous close) and intraday spikes.
     */
    public BigDecimal gapsAndSpikes(BigDecimal currentPrice, BigDecimal baseVolatility) {
        // Gap-up or gap-down (2% probability)
        if (random.nextDouble() < 0.02) {
            double gapSize = (random.nextDouble() + 0.5) * 0.02;  // 0.5% to 2.5%
            double direction = random.nextBoolean() ? 1 : -1;
            BigDecimal gapped = currentPrice.multiply(BigDecimal.valueOf(1.0 + (gapSize * direction)));
            log.debug("synthetic.gap direction={} size={}", direction > 0 ? "UP" : "DOWN", gapSize);
            return gapped.setScale(8, RoundingMode.HALF_UP);
        }

        // Intraday spike (sudden move + revert)
        if (random.nextDouble() < 0.01) {  // 1% probability
            double spikeSize = random.nextDouble() * 0.05;  // up to 5%
            double spikeDirection = random.nextBoolean() ? 1 : -1;

            // Price moves 5%, then reverts 80% back
            double revert = spikeSize * spikeDirection * 0.8;
            double noise = random.nextGaussian() * baseVolatility.doubleValue();
            double change = revert + noise;

            BigDecimal newPrice = currentPrice.multiply(BigDecimal.valueOf(1.0 + change));
            log.debug("synthetic.spike size={}", spikeSize);
            return newPrice.setScale(8, RoundingMode.HALF_UP);
        }

        // Normal move
        double change = random.nextGaussian() * baseVolatility.doubleValue();
        BigDecimal newPrice = currentPrice.multiply(BigDecimal.valueOf(1.0 + change));
        return newPrice.setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Generate a complete synthetic candle.
     */
    public MarketdataCandle generateCandle(String symbol, Instant openTime, BigDecimal openPrice,
                                           BigDecimal volatility) {
        // Simulate high/low within the candle
        BigDecimal high = openPrice;
        BigDecimal low = openPrice;
        BigDecimal close = openPrice;

        for (int i = 0; i < 4; i++) {
            close = randomWalk(close, volatility);
            high = high.max(close);
            low = low.min(close);
        }

        // Synthetic volume (random 10K - 100K)
        BigDecimal volume = BigDecimal.valueOf(10_000 + random.nextInt(90_000));

        MarketdataCandle candle = new MarketdataCandle();
        candle.setSymbol(symbol);
        candle.setTimeframe("1m");
        candle.setOpenTime(openTime);
        candle.setOpenPrice(openPrice);
        candle.setHighPrice(high);
        candle.setLowPrice(low);
        candle.setClosePrice(close);
        candle.setVolume(volume);
        return candle;
    }
}
