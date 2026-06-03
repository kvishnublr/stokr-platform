package com.stokr.bootstrap.operational;

import com.stokr.bootstrap.recovery.PlatformRecoveryProperties;
import com.stokr.bootstrap.recovery.RankedRecoveryOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Platform auto-heal entry point — delegates to {@link RankedRecoveryOrchestrator}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformOperationalAutoHealScheduler {

    private final RankedRecoveryOrchestrator orchestrator;
    private final PlatformRecoveryProperties properties;

    @Scheduled(fixedDelayString = "${stokr.platform.recovery.interval-ms:45000}")
    public void healOperationalLayers() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            orchestrator.runRecoveryCycle();
        } catch (Exception ex) {
            log.warn("platform.recovery cycle_error {}", ex.toString());
        }
    }
}
