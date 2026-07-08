package com.stokr.risk;

import com.stokr.engine.DeploymentService;
import com.stokr.engine.Deployment;
import com.stokr.engine.ExitManager;
import com.stokr.marketdata.MarketDataService;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.strategy.Strategy;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Auto-square-off service that closes all intraday positions at EOD.
 * DAILY strategies (positional, multi-day holds) are skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EodSquareOffService {

    private final DeploymentService deploymentService;
    private final PositionService positionService;
    private final ExitManager exitManager;
    private final MarketDataService marketDataService;
    private final StrategyService strategyService;

    /**
     * Runs at 15:15 IST every trading day to square off all open positions.
     */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void triggerEodSquareOff() {
        log.info("=== EOD SQUARE-OFF TRIGGERED ===");

        List<Deployment> activeDeployments = deploymentService.getAllActiveDeployments();
        int totalClosed = 0;

        for (Deployment deployment : activeDeployments) {
            try {
                Strategy strategy = strategyService.getStrategy(deployment.getStrategyId());
                if ("DAILY".equalsIgnoreCase(strategy.getTimeframe())) {
                    log.debug("EOD square-off: skipping DAILY deployment {} ({})",
                            deployment.getId(), strategy.getName());
                    continue;
                }
            } catch (Exception e) {
                log.warn("EOD square-off: could not check timeframe for deployment {}, proceeding with square-off",
                        deployment.getId());
            }

            List<Position> openPositions = positionService.getOpenPositions(deployment.getId());
            if (openPositions.isEmpty()) continue;

            log.info("EOD square-off for deployment {}: {} positions",
                    deployment.getId(), openPositions.size());

            for (Position position : openPositions) {
                try {
                    BigDecimal ltp = marketDataService.getLtp(position.getSymbol());
                    if (ltp.compareTo(BigDecimal.ZERO) <= 0) {
                        ltp = position.getAvgPrice();
                    }
                    exitManager.squareOffPosition(deployment, position, ltp);
                    totalClosed++;
                } catch (Exception e) {
                    log.error("EOD square-off failed for deployment {} position {}",
                            deployment.getId(), position.getSymbol(), e);
                }
            }
        }

        log.info("=== EOD SQUARE-OFF COMPLETE: {} positions closed ===", totalClosed);
    }
}
