package com.stokr.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoHealthFixScheduler {

    private final AdminSystemHealthFixService systemHealthFixService;

    private static final UUID PRIMARY_TRADER = UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");
    private static volatile boolean hasRun = false;

    /**
     * Run health fix automatically on application startup
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runHealthFixOnStartup() {
        if (hasRun) {
            log.info("auto_health_fix.already_ran");
            return;
        }

        hasRun = true;

        try {
            log.info("auto_health_fix.starting primary_trader={}", PRIMARY_TRADER);

            var results = systemHealthFixService.executeAllFixes(PRIMARY_TRADER);

            log.info("auto_health_fix.completed results={}", results);

            // Log summary
            if (results.containsKey("cleared_zero_price_ghosts")) {
                var ghosts = (java.util.Map<String, Object>) results.get("cleared_zero_price_ghosts");
                log.info("auto_health_fix.cleared_ghosts count={}", ghosts.get("cleared_count"));
            }

            if (results.containsKey("expired_stuck_orders")) {
                var stuck = (java.util.Map<String, Object>) results.get("expired_stuck_orders");
                log.info("auto_health_fix.expired_stuck_orders count={}", stuck.get("expired_count"));
            }

            if (results.containsKey("deleted_integrity_failures")) {
                var integrity = (java.util.Map<String, Object>) results.get("deleted_integrity_failures");
                log.info("auto_health_fix.integrity_cleaned count={}", integrity.get("rejected_count"));
            }

        } catch (Exception e) {
            log.error("auto_health_fix.error error={}", e.getMessage(), e);
        }
    }
}
