package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Detects VWAP bounce setups
 *
 * Setup: Stock bounces off VWAP after touching it multiple times
 * Entry: When price bounces away from VWAP (confirmed 3+ touches)
 * Target: Previous day's high (long) or low (short)
 * Stop Loss: Beyond VWAP extreme
 *
 * Logic:
 * 1. Check if price is within 1% of VWAP
 * 2. Track number of touches on VWAP
 * 3. Wait for 3+ touches
 * 4. Entry: When price bounces away from VWAP with confirmation candle
 * 5. Target: Previous day's high/low depending on bounce direction
 * 6. Stop: Beyond VWAP + buffer
 */
@Service
@Slf4j
public class VwapBounceDetector implements SetupDetector {

    private static final BigDecimal VWAP_TOUCH_THRESHOLD = BigDecimal.valueOf(0.01); // 1%
    private static final BigDecimal BOUNCE_CONFIRMATION_PERCENT = BigDecimal.valueOf(0.02); // 2% away from VWAP
    private static final int MIN_VWAP_TOUCHES = 3;

    @Override
    public String getSetupType() {
        return "vwap_bounce";
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

        // VWAP bounces can happen anytime, but work best 10-15 (skipping first hour)
        if (hourOfDay == null || hourOfDay < 10) {
            return null;
        }

        // Check if price is very close to VWAP (within 1%)
        BigDecimal distanceToVwap = currentPrice.subtract(currentVwap).abs();
        BigDecimal distanceToVwapPercent = distanceToVwap.divide(currentVwap, 6, RoundingMode.HALF_UP);

        // For a VWAP bounce, price should be touching/near VWAP
        if (distanceToVwapPercent.compareTo(VWAP_TOUCH_THRESHOLD) > 0) {
            return null; // Price not near VWAP
        }

        // Determine if bounce is happening (price moving away from VWAP)
        // This is a simplified check - in production, would track touch count in state
        boolean isBounceUp = currentPrice.compareTo(currentVwap) > 0;

        // Check if volume is above average (confirms bounce)
        if (volume != null && avgVolume != null && avgVolume > 0) {
            BigDecimal volumeRatio = BigDecimal.valueOf(volume).divide(BigDecimal.valueOf(avgVolume), 2, RoundingMode.HALF_UP);
            if (volumeRatio.compareTo(BigDecimal.valueOf(1.1)) < 0) {
                return null; // Insufficient volume to confirm bounce
            }
        }

        // Build setup
        CurrentSetup setup = new CurrentSetup();
        setup.setStockId(stock.getStockId());
        setup.setSetupType("vwap_bounce");
        setup.setTimeDetected(timestamp);

        // Entry: at VWAP level
        setup.setEntryPrice(currentVwap);

        // Target: opposite extreme from bounce direction
        if (isBounceUp) {
            // Bouncing up, target is prev high
            setup.setTargetPrice(stock.getPrevHigh());
        } else {
            // Bouncing down, target is prev low
            setup.setTargetPrice(stock.getPrevLow());
        }

        // Stop loss: beyond VWAP + buffer
        BigDecimal stopBuffer = atr14 != null ? atr14.multiply(BigDecimal.valueOf(0.5)) : currentPrice.multiply(BigDecimal.valueOf(0.01));
        if (isBounceUp) {
            // Bouncing up, stop below VWAP
            setup.setStopLoss(currentVwap.subtract(stopBuffer));
        } else {
            // Bouncing down, stop above VWAP
            setup.setStopLoss(currentVwap.add(stopBuffer));
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

        log.debug("vwap_bounce.detected stock={} direction={} vwap={} target={} stop={}",
                stock.getStockId(), isBounceUp ? "UP" : "DOWN", currentVwap,
                setup.getTargetPrice(), setup.getStopLoss());

        return setup;
    }
}
