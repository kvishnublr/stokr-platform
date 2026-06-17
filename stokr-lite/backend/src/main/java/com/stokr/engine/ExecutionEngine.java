package com.stokr.engine;

import com.stokr.marketdata.MarketDataService;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.risk.KillSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEngine {

    private final DeploymentService deploymentService;
    private final SignalProcessor signalProcessor;
    private final MarketDataService marketDataService;
    private final KillSwitchService killSwitchService;
    private final PositionService positionService;
    private final ExitManager exitManager;

    private static final LocalTime EOD_SQUAREOFF = LocalTime.of(15, 15);

    /**
     * Main execution loop - called by scheduler every scan interval.
     */
    public void runScanCycle() {
        if (!marketDataService.isMarketOpen()) {
            log.debug("Market closed, skipping scan cycle");
            return;
        }

        if (killSwitchService.isActive()) {
            log.warn("Kill switch active, skipping scan cycle");
            return;
        }

        List<Deployment> activeDeployments = deploymentService.getAllActiveDeployments();
        if (activeDeployments.isEmpty()) {
            log.debug("No active deployments, skipping scan cycle");
            return;
        }

        log.info("Starting scan cycle for {} active deployments", activeDeployments.size());

        boolean isEodSquareOff = LocalTime.now().isAfter(EOD_SQUAREOFF);

        for (Deployment deployment : activeDeployments) {
            try {
                if (isEodSquareOff) {
                    // EOD: Square off all open positions
                    squareOffAllPositions(deployment);
                } else {
                    // Normal cycle: process exits first, then entries
                    processExits(deployment);
                    signalProcessor.processDeployment(deployment);
                }
            } catch (Exception e) {
                log.error("Error in scan cycle for deployment {}", deployment.getId(), e);
            }
        }

        log.info("Scan cycle complete");
    }

    /**
     * Check open positions for exit conditions (stop loss, target hit).
     */
    private void processExits(Deployment deployment) {
        List<Position> openPositions = positionService.getOpenPositions(deployment.getId());
        if (openPositions.isEmpty()) return;

        for (Position position : openPositions) {
            try {
                BigDecimal ltp = marketDataService.getLtp(position.getSymbol());
                if (ltp.compareTo(BigDecimal.ZERO) <= 0) continue;

                // For now, exit logic is handled by the strategy signal processor
                // In production, each strategy should define exit conditions
                // (stop loss, target, trailing stop, time-based exit, etc.)
            } catch (Exception e) {
                log.error("Error processing exit for deployment {} position {}",
                        deployment.getId(), position.getSymbol(), e);
            }
        }
    }

    /**
     * EOD square-off: close all open positions for a deployment.
     */
    private void squareOffAllPositions(Deployment deployment) {
        List<Position> openPositions = positionService.getOpenPositions(deployment.getId());
        if (openPositions.isEmpty()) return;

        log.info("EOD square-off for deployment {}: {} open positions",
                deployment.getId(), openPositions.size());

        for (Position position : openPositions) {
            try {
                BigDecimal ltp = marketDataService.getLtp(position.getSymbol());
                if (ltp.compareTo(BigDecimal.ZERO) <= 0) {
                    ltp = position.getAvgPrice(); // Fallback to avg price if LTP unavailable
                }
                exitManager.squareOffPosition(deployment, position, ltp);
            } catch (Exception e) {
                log.error("EOD square-off failed for deployment {} position {}",
                        deployment.getId(), position.getSymbol(), e);
            }
        }
    }
}
