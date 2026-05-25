package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Detects gap fill setups
 *
 * Setup: Stock opens with gap from previous close, then fills the gap
 * Entry: When price returns within 1% of prev close
 * Target: Previous day's high/low (opposite direction)
 * Stop Loss: Beyond gap extreme
 *
 * Logic:
 * 1. Check for gap: |open - prev_close| > 0.3% OR |open - prev_close| > ATR*0.5
 * 2. Check gap direction: UP (open > prev_close) or DOWN (open < prev_close)
 * 3. Wait for partial fill: Price crosses 50% of gap
 * 4. Entry: When price returns to within 1% of prev_close
 * 5. Target: Previous day's high (if gap down) or low (if gap up)
 * 6. Stop: Beyond the gap extreme
 */
@Service
@Slf4j
public class GapFillDetector implements SetupDetector {

    private static final BigDecimal GAP_THRESHOLD_PERCENT = BigDecimal.valueOf(0.003); // 0.3%
    private static final BigDecimal FILL_THRESHOLD_PERCENT = BigDecimal.valueOf(0.01); // 1%
    private static final BigDecimal PARTIAL_FILL_PERCENT = BigDecimal.valueOf(0.5); // 50% of gap

    @Override
    public String getSetupType() {
        return "gap_fill";
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

        // Only detect gap fills in first hour (9:30-10:30)
        if (hourOfDay == null || hourOfDay > 10) {
            return null;
        }

        // Calculate gap size
        BigDecimal gapSize = openPrice.subtract(prevClose).abs();
        BigDecimal gapSizePercent = gapSize.divide(prevClose, 6, RoundingMode.HALF_UP);

        // Check for meaningful gap
        BigDecimal minGapPercent = GAP_THRESHOLD_PERCENT;
        if (atr14 != null) {
            BigDecimal atrGapThreshold = atr14.multiply(BigDecimal.valueOf(0.5))
                    .divide(prevClose, 6, RoundingMode.HALF_UP);
            minGapPercent = minGapPercent.max(atrGapThreshold);
        }

        if (gapSizePercent.compareTo(minGapPercent) < 0) {
            return null; // No significant gap
        }

        // Determine gap direction
        boolean isGapUp = openPrice.compareTo(prevClose) > 0;

        // Check if gap is being filled (price moving back towards prev close)
        BigDecimal gapCenter = prevClose;
        BigDecimal distanceToPrevClose = currentPrice.subtract(gapCenter).abs();
        BigDecimal distanceToPrevClosePercent = distanceToPrevClose.divide(prevClose, 6, RoundingMode.HALF_UP);

        // Has gap been partially filled? (price crossed 50% of gap)
        boolean isPartiallyFilled = gapSizePercent.multiply(PARTIAL_FILL_PERCENT)
                .compareTo(distanceToPrevClose.divide(prevClose, 6, RoundingMode.HALF_UP)) >= 0;

        if (!isPartiallyFilled) {
            return null; // Gap not yet starting to fill
        }

        // Is price close to prev close? (within 1%)
        if (distanceToPrevClosePercent.compareTo(FILL_THRESHOLD_PERCENT) > 0) {
            return null; // Not yet at entry price
        }

        // Build setup
        CurrentSetup setup = new CurrentSetup();
        setup.setStockId(stock.getStockId());
        setup.setSetupType("gap_fill");
        setup.setTimeDetected(timestamp);

        // Entry: at prev close
        setup.setEntryPrice(prevClose);

        // Target: opposite extreme from gap
        if (isGapUp) {
            // Gap up, target is prev low
            setup.setTargetPrice(stock.getPrevLow());
        } else {
            // Gap down, target is prev high
            setup.setTargetPrice(stock.getPrevHigh());
        }

        // Stop loss: beyond gap extreme + buffer
        BigDecimal stopBuffer = atr14 != null ? atr14.multiply(BigDecimal.valueOf(0.3)) : gapSize;
        if (isGapUp) {
            // Gap up, stop above open
            setup.setStopLoss(openPrice.add(stopBuffer));
        } else {
            // Gap down, stop below open
            setup.setStopLoss(openPrice.subtract(stopBuffer));
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

        log.debug("gap_fill.detected stock={} direction={} gap_pct={} target={} stop={}",
                stock.getStockId(), isGapUp ? "UP" : "DOWN", gapSizePercent,
                setup.getTargetPrice(), setup.getStopLoss());

        return setup;
    }
}
