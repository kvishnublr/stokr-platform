package com.stokr.bootstrap.trading;

import com.stokr.execution.service.PositionExitOrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * ConfidenceSignalExitService
 *
 * Monitors open confidence-based trades and automatically closes positions
 * based on profit targets and stop losses.
 *
 * Exit Levels (configurable):
 * - High confidence (>=80): +2% target, -1% SL
 * - Medium confidence (70-80): +1.5% target, -1.5% SL
 * - Low confidence (<70): +1% target, -2% SL
 *
 * Also closes all positions at market close (15:30 IST)
 */
@Slf4j
@Service
@ConditionalOnProperty(
    name = "stokr.confidence-strategy.auto-trade-enabled",
    havingValue = "true",
    matchIfMissing = false
)
@RequiredArgsConstructor
public class ConfidenceSignalExitService {

    private static final java.util.UUID PRIMARY_TRADER_ID = java.util.UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");

    private final PositionExitOrchestratorService positionExitOrchestratorService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private String marketZone;

    @Value("${stokr.marketdata.session.nse.end:15:30}")
    private String nseEndTime;

    @Value("${stokr.confidence-strategy.exit.profit-target-high:2.0}")
    private double profitTargetHigh; // 2% for confidence >= 80

    @Value("${stokr.confidence-strategy.exit.profit-target-medium:1.5}")
    private double profitTargetMedium; // 1.5% for confidence 70-80

    @Value("${stokr.confidence-strategy.exit.profit-target-low:1.0}")
    private double profitTargetLow; // 1% for confidence < 70

    @Value("${stokr.confidence-strategy.exit.stop-loss-high:1.0}")
    private double stopLossHigh; // 1% SL for confidence >= 80

    @Value("${stokr.confidence-strategy.exit.stop-loss-medium:1.5}")
    private double stopLossMedium; // 1.5% SL for confidence 70-80

    @Value("${stokr.confidence-strategy.exit.stop-loss-low:2.0}")
    private double stopLossLow; // 2% SL for confidence < 70

    /**
     * Runs every minute to check for positions that should be closed.
     */
    @Scheduled(fixedRateString = "${stokr.confidence-strategy.exit.check-interval-ms:60000}",
               initialDelayString = "${stokr.confidence-strategy.exit.initial-delay-ms:10000}")
    @Transactional
    public void checkAndClosePositions() {
        try {
            log.debug("Exit service running - checking for positions to close");

            boolean nearMarketClose = isNearMarketClose();
            if (nearMarketClose) {
                var result = positionExitOrchestratorService.flattenAll(
                        PRIMARY_TRADER_ID,
                        "CONFIDENCE_MARKET_CLOSE",
                        "CONFIDENCE_EXIT: Market close safeguard"
                );
                log.info("confidence_exit.market_close_flatten ordersCreated={} notes={}",
                        result.get("ordersCreated"), result.get("notes"));
            }

            log.debug("Exit check complete");

        } catch (Exception e) {
            log.error("Fatal error in exit service: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if market is within 10 minutes of close (15:20-15:30)
     */
    private boolean isNearMarketClose() {
        try {
            ZoneId zone = ZoneId.of(marketZone);
            ZonedDateTime now = ZonedDateTime.now(zone);
            LocalTime currentTime = now.toLocalTime();
            LocalTime nseEnd = LocalTime.parse(nseEndTime);
            LocalTime closeWindowStart = nseEnd.minusMinutes(10); // 15:20

            boolean nearClose = !currentTime.isBefore(closeWindowStart) && currentTime.isBefore(nseEnd);

            if (nearClose) {
                log.debug("Market close window: {} - {}", closeWindowStart, nseEnd);
            }

            return nearClose;

        } catch (Exception e) {
            log.debug("Error checking market close time: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Determine profit target based on confidence level.
     * Kept for config parity, though the live exit now routes through the real flatten path.
     */
    private double determineProfitTarget(double confidence) {
        if (confidence >= 80) {
            return profitTargetHigh;
        } else if (confidence >= 70) {
            return profitTargetMedium;
        } else {
            return profitTargetLow;
        }
    }

    /**
     * Determine stop loss based on confidence level.
     * Kept for config parity, though the live exit now routes through the real flatten path.
     */
    private double determineStopLoss(double confidence) {
        if (confidence >= 80) {
            return stopLossHigh;
        } else if (confidence >= 70) {
            return stopLossMedium;
        } else {
            return stopLossLow;
        }
    }
}
