package com.stokr.oms.service;

import com.stokr.oms.portfolio.PortfolioAccountingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically expires OMS orders that are stuck in pre-terminal states.
 * Prevents the "OMS DEGRADED stuck-N" condition from persisting indefinitely.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OmsAutoHealScheduler {

    private static final int STUCK_THRESHOLD_MINUTES = 8;

    private final OrderLifecycleService orderLifecycleService;
    private final PortfolioAccountingService portfolioAccountingService;

    @Scheduled(fixedDelay = 120_000)
    public void expireStuckOrders() {
        try {
            int expired = orderLifecycleService.forceExpireStuckOrders(STUCK_THRESHOLD_MINUTES);
            if (expired > 0) {
                log.warn("oms.auto_heal.expired count={} stuckMinutes={}", expired, STUCK_THRESHOLD_MINUTES);
            }
        } catch (Exception ex) {
            log.error("oms.auto_heal.error {}", ex.getMessage(), ex);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void clearPortfolioGhosts() {
        try {
            int cleared = portfolioAccountingService.clearZeroPriceGhostPositions();
            if (cleared > 0) {
                log.warn("oms.auto_heal.portfolio_ghosts_cleared count={}", cleared);
            }
        } catch (Exception ex) {
            log.error("oms.auto_heal.portfolio_ghosts_error {}", ex.getMessage(), ex);
        }
    }
}
