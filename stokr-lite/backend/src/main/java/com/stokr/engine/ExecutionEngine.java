package com.stokr.engine;

import com.stokr.marketdata.MarketDataService;
import com.stokr.risk.KillSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEngine {

    private final DeploymentService deploymentService;
    private final SignalProcessor signalProcessor;
    private final MarketDataService marketDataService;
    private final KillSwitchService killSwitchService;

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

        for (Deployment deployment : activeDeployments) {
            try {
                signalProcessor.processDeployment(deployment);
            } catch (Exception e) {
                log.error("Error in scan cycle for deployment {}", deployment.getId(), e);
            }
        }

        log.info("Scan cycle complete");
    }
}
