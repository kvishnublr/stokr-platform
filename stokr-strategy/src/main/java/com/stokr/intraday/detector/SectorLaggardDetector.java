package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Detects sector laggard setups
 *
 * Setup: Stock underperforms its sector while sector is strong
 * Entry: When stock price bounces after lagging sector by >2%
 * Target: Bring stock in line with sector momentum (catch-up)
 * Stop Loss: Below entry if sector momentum reverses
 *
 * Logic:
 * 1. Check sector momentum (sector_return > 1%)
 * 2. Check individual stock is lagging sector (stock_return < sector_return - 2%)
 * 3. Wait for stock to show strength (volume above avg, price above entry support)
 * 4. Entry: When stock starts catching up with volume confirmation
 * 5. Target: Previous day's high OR sector_return equivalent move
 * 6. Stop: Below entry support level
 */
@Service
@Slf4j
public class SectorLaggardDetector implements SetupDetector {

    private static final BigDecimal SECTOR_MOMENTUM_THRESHOLD = BigDecimal.valueOf(0.01); // 1%
    private static final BigDecimal LAG_THRESHOLD = BigDecimal.valueOf(0.02); // 2% lag required
    private static final BigDecimal REVERSAL_CONFIRMATION = BigDecimal.valueOf(0.01); // 1% recovery move

    @Override
    public String getSetupType() {
        return "sector_laggard";
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

        // Sector laggards work best mid-day (10-14)
        if (hourOfDay == null || hourOfDay < 10 || hourOfDay > 14) {
            return null;
        }

        // Calculate stock's return from open
        BigDecimal stockReturn = currentPrice.subtract(openPrice)
                .divide(openPrice, 6, RoundingMode.HALF_UP);

        // Note: In production, sectorMomentum would come from sector_tracking table
        // For now, this is simplified - assumes we have sector momentum data
        // Placeholder: would need to inject SectorTrackingRepository
        BigDecimal sectorMomentum = BigDecimal.ZERO; // Would be fetched from sector_tracking

        // Check if sector is strong (positive momentum)
        if (sectorMomentum.compareTo(SECTOR_MOMENTUM_THRESHOLD) < 0) {
            return null; // Sector not strong enough
        }

        // Check if stock is lagging sector significantly
        BigDecimal lag = sectorMomentum.subtract(stockReturn);
        if (lag.compareTo(LAG_THRESHOLD) < 0) {
            return null; // Stock is not lagging enough
        }

        // Check for recovery confirmation (stock starting to bounce back)
        // Price should be recovering from intraday lows
        if (currentPrice.compareTo(prevClose) < 0) {
            return null; // Stock hasn't recovered to at least prev close
        }

        // Check volume confirmation (strong volume suggests reversal)
        if (volume != null && avgVolume != null && avgVolume > 0) {
            BigDecimal volumeRatio = BigDecimal.valueOf(volume).divide(BigDecimal.valueOf(avgVolume), 2, RoundingMode.HALF_UP);
            if (volumeRatio.compareTo(BigDecimal.valueOf(1.0)) < 0) {
                return null; // Volume not confirming reversal
            }
        }

        // Build setup
        CurrentSetup setup = new CurrentSetup();
        setup.setStockId(stock.getStockId());
        setup.setSetupType("sector_laggard");
        setup.setTimeDetected(timestamp);

        // Entry: at current price (stock recovering)
        setup.setEntryPrice(currentPrice);

        // Target: Prev high or gap-adjusted high
        setup.setTargetPrice(stock.getPrevHigh());

        // Stop loss: Below support (VWAP or intraday low + buffer)
        BigDecimal stopBuffer = atr14 != null ? atr14.multiply(BigDecimal.valueOf(0.4)) : currentPrice.multiply(BigDecimal.valueOf(0.005));
        setup.setStopLoss(currentVwap.subtract(stopBuffer));

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

        log.debug("sector_laggard.detected stock={} lag={} sector_momentum={} target={} stop={}",
                stock.getStockId(), lag, sectorMomentum,
                setup.getTargetPrice(), setup.getStopLoss());

        return setup;
    }
}
