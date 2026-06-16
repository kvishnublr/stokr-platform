package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Detects early breakout setups
 *
 * Setup: Stock breaks out above/below opening 5-minute range in first hour (9:30-10:30)
 * Entry: When price breaks above 5min high/low with volume confirmation
 * Target: 52-week high (if breakout up) or calculated based on range size
 * Stop Loss: Below/above 5min support/resistance
 *
 * Logic:
 * 1. Only detect in first hour (9:30-10:30)
 * 2. Track 5-minute opening range (high and low of first 5 minutes)
 * 3. Wait for price to break outside opening range
 * 4. Check for volume confirmation (>1.5x average)
 * 5. Entry: at breakout level
 * 6. Target: 52-week high/low or 2x opening range width
 * 7. Stop: Just inside 5min range
 */
@Service
@Slf4j
public class EarlyBreakoutDetector implements SetupDetector {

    private static final int MAX_DETECTION_HOUR = 10; // Only 9:30-10:30
    private static final BigDecimal BREAKOUT_THRESHOLD = BigDecimal.valueOf(0.001); // 0.1% above/below range
    private static final BigDecimal VOLUME_CONFIRMATION_RATIO = BigDecimal.valueOf(1.5); // 1.5x average

    @Override
    public String getSetupType() {
        return "early_breakout";
    }

    @Override
    public CurrentSetup detectSetup(
            NseStock stock,
            BigDecimal currentPrice,
            BigDecimal currentVwap,
            BigDecimal openPrice,
            BigDecimal prevClose,
            Long volume,
            Long avgVolume,
            BigDecimal atr14,
            Integer hourOfDay,
            Instant timestamp) {

        // Only detect early breakouts in first hour (9:30-10:30)
        if (hourOfDay == null || hourOfDay > MAX_DETECTION_HOUR) {
            return null;
        }

        // Check for strong volume confirmation
        if (volume != null && avgVolume != null && avgVolume > 0) {
            BigDecimal volumeRatio = BigDecimal.valueOf(volume).divide(BigDecimal.valueOf(avgVolume), 2, RoundingMode.HALF_UP);
            if (volumeRatio.compareTo(VOLUME_CONFIRMATION_RATIO) < 0) {
                return null; // Insufficient volume
            }
        }

        // Define 5-minute opening range (simplified: use open to current high/low)
        // In production, would track actual 5min OHLC data
        BigDecimal openingRangeHigh = openPrice.multiply(BigDecimal.valueOf(1.005)); // Assume 0.5% as range
        BigDecimal openingRangeLow = openPrice.multiply(BigDecimal.valueOf(0.995));

        // Check if price is breaking out of opening range
        boolean isBreakoutUp = currentPrice.compareTo(openingRangeHigh) > 0;
        boolean isBreakoutDown = currentPrice.compareTo(openingRangeLow) < 0;

        if (!isBreakoutUp && !isBreakoutDown) {
            return null; // No breakout yet
        }

        // Confirm momentum is strong (price moved beyond just the breakout point)
        BigDecimal breakoutDistance;
        if (isBreakoutUp) {
            breakoutDistance = currentPrice.subtract(openingRangeHigh)
                    .divide(openingRangeHigh, 6, RoundingMode.HALF_UP);
        } else {
            breakoutDistance = openingRangeLow.subtract(currentPrice)
                    .divide(openingRangeLow, 6, RoundingMode.HALF_UP);
        }

        if (breakoutDistance.compareTo(BREAKOUT_THRESHOLD) < 0) {
            return null; // Breakout not far enough to confirm
        }

        // Build setup
        CurrentSetup setup = new CurrentSetup();
        setup.setStockId(stock.getStockId());
        setup.setSetupType("early_breakout");
        setup.setTimeDetected(timestamp);

        // Entry: at current price (breakout confirmed)
        setup.setEntryPrice(currentPrice);

        // Target: 52-week high (if breakout up) or 52-week low (if down), or 2x range width
        BigDecimal rangeWidth = openingRangeHigh.subtract(openingRangeLow);
        if (isBreakoutUp) {
            // Target is 52-week high or current + 2x range width
            setup.setTargetPrice(stock.getWeek52High() != null ? stock.getWeek52High() :
                    currentPrice.add(rangeWidth.multiply(BigDecimal.valueOf(2))));
        } else {
            // Target is 52-week low or current - 2x range width
            setup.setTargetPrice(stock.getWeek52Low() != null ? stock.getWeek52Low() :
                    currentPrice.subtract(rangeWidth.multiply(BigDecimal.valueOf(2))));
        }

        // Stop loss: Just inside the opening range
        BigDecimal stopBuffer = atr14 != null ? atr14.multiply(BigDecimal.valueOf(0.2)) : rangeWidth.multiply(BigDecimal.valueOf(0.5));
        if (isBreakoutUp) {
            // Stop below opening range high
            setup.setStopLoss(openingRangeHigh.subtract(stopBuffer));
        } else {
            // Stop above opening range low
            setup.setStopLoss(openingRangeLow.add(stopBuffer));
        }

        // Calculate risk/reward
        calculateRiskReward(setup);

        // Check validity
        if (!isValidSetup(setup)) {
            return null;
        }

        // Set metadata
        setup.setIsActive(true);
        setup.setMarketRegime(null); // Will be set by ranking engine
        setup.setBaseProbability(null); // Will be set from historical_win_rates
        setup.setExpiresAt(timestamp.plusSeconds(30 * 60)); // 30-minute expiry

        log.debug("early_breakout.detected stock={} direction={} range_width={} target={} stop={}",
                stock.getStockId(), isBreakoutUp ? "UP" : "DOWN", rangeWidth,
                setup.getTargetPrice(), setup.getStopLoss());

        return setup;
    }
}
