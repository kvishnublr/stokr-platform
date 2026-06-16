package com.stokr.engine;

import com.stokr.broker.*;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.risk.ErrorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionReconciler {

    private final DeploymentService deploymentService;
    private final BrokerService brokerService;
    private final PositionService positionService;
    private final ErrorLogService errorLogService;

    /**
     * On startup, reconcile local positions with broker positions.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("=== STARTUP POSITION RECONCILIATION ===");

        List<Deployment> activeDeployments = deploymentService.getAllActiveDeployments();
        for (Deployment deployment : activeDeployments) {
            if (!deployment.isLive()) continue;

            try {
                BrokerAccount brokerAccount = brokerService.getBrokerAccount(
                        deployment.getBrokerAccountId(), deployment.getUserId());

                if (brokerAccount.isTokenExpired()) {
                    log.warn("Token expired for broker account {}, skipping reconciliation",
                            brokerAccount.getId());
                    continue;
                }

                BrokerAdapter adapter = brokerService.getAdapter(brokerAccount.getBrokerName());
                List<BrokerPosition> brokerPositions = adapter.getPositions(brokerAccount.getAccessToken());
                List<Position> localPositions = positionService.getOpenPositions(deployment.getId());

                // Check for discrepancies
                for (Position localPos : localPositions) {
                    boolean found = brokerPositions.stream()
                            .anyMatch(bp -> bp.symbol().equals(localPos.getSymbol()));
                    if (!found) {
                        log.warn("MISMATCH: Local position {} exists for deployment {} but not at broker",
                                localPos.getSymbol(), deployment.getId());
                        errorLogService.logError(deployment.getId(), "POSITION_MISMATCH",
                                "Local position " + localPos.getSymbol() + " not found at broker",
                                null, "WARN");
                    }
                }
            } catch (Exception e) {
                log.error("Reconciliation failed for deployment {}", deployment.getId(), e);
            }
        }

        log.info("=== RECONCILIATION COMPLETE ===");
    }
}
