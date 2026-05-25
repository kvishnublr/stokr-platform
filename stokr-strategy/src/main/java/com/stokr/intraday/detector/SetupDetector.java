package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Interface for setup detection algorithms
 * Each implementation detects a different setup type:
 * - GapFillDetector: Previous day gap fills
 * - VwapBounceDetector: Bounces off VWAP
 * - SectorLaggardDetector: Stocks lagging sector momentum
 * - EarlyBreakoutDetector: Breakouts in first hour (9:30-10:30)
 */
public interface SetupDetector {

    /**
     * Get the setup type this detector handles
     */
    String getSetupType();

    /**
     * Evaluate if a setup exists for this stock
     *
     * @param stock Stock reference data
     * @param currentPrice Current price
     * @param currentVwap Current VWAP
     * @param openPrice Day's opening price
     * @param prevClose Previous day's close
     * @param volume Current volume
     * @param avgVolume 20-day average volume
     * @param atr14 14-period ATR
     * @param hourOfDay Current hour (9-15)
     * @param timestamp Current time
     * @return CurrentSetup if detected, null otherwise
     */
    CurrentSetup detectSetup(
            NseStock stock,
            BigDecimal currentPrice,
            BigDecimal currentVwap,
            BigDecimal openPrice,
            BigDecimal prevClose,
            Long volume,
            Long avgVolume,
            BigDecimal atr14,
            Integer hourOfDay,
            Instant timestamp);

    /**
     * Validate if detected setup meets minimum criteria
     * - Risk/reward ratio >= 1.5
     * - Stop loss < entry < target
     * - Setup age < 30 minutes (not stale)
     */
    default boolean isValidSetup(CurrentSetup setup) {
        if (setup == null) {
            return false;
        }

        // Check risk/reward ratio
        if (setup.getRiskRewardRatio() == null ||
            setup.getRiskRewardRatio().compareTo(BigDecimal.valueOf(1.5)) < 0) {
            return false;
        }

        // Check price levels are valid
        if (setup.getEntryPrice() == null || setup.getStopLoss() == null ||
            setup.getTargetPrice() == null) {
            return false;
        }

        if (setup.getStopLoss().compareTo(setup.getEntryPrice()) >= 0 ||
            setup.getTargetPrice().compareTo(setup.getEntryPrice()) <= 0) {
            return false;
        }

        // Check not stale (detected within last 30 minutes)
        if (setup.getTimeDetected() != null) {
            long ageMinutes = java.time.temporal.ChronoUnit.MINUTES
                    .between(setup.getTimeDetected(), Instant.now());
            if (ageMinutes > 30) {
                return false;
            }
        }

        return true;
    }

    /**
     * Calculate risk and reward amounts
     */
    default void calculateRiskReward(CurrentSetup setup) {
        if (setup.getEntryPrice() == null || setup.getStopLoss() == null ||
            setup.getTargetPrice() == null) {
            return;
        }

        BigDecimal riskPerShare = setup.getEntryPrice().subtract(setup.getStopLoss()).abs();
        BigDecimal rewardPerShare = setup.getTargetPrice().subtract(setup.getEntryPrice()).abs();

        setup.setRiskAmount(riskPerShare);
        setup.setRewardAmount(rewardPerShare);

        if (riskPerShare.signum() > 0) {
            setup.setRiskRewardRatio(
                    rewardPerShare.divide(riskPerShare, 2, java.math.RoundingMode.HALF_UP)
            );
        }
    }
}
