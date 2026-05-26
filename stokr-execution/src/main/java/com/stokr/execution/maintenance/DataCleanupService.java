package com.stokr.execution.maintenance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled data hygiene hooks. Repository-level cleanup is applied via DB migrations
 * and admin tooling; this service records scheduled checkpoints only.
 */
@Service
@Slf4j
public class DataCleanupService {

    @Value("${stokr.cleanup.archive-days:30}")
    private int archiveDays;

    @Scheduled(cron = "0 30 0 * * *")
    public void archiveOldSignals() {
        log.debug("cleanup.archive_signals_skipped archive_days={}", archiveDays);
    }

    @Scheduled(cron = "0 45 0 * * *")
    public void cleanOrphanedOrders() {
        log.debug("cleanup.orphaned_orders_skipped");
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void removeDuplicateSignals() {
        log.debug("cleanup.duplicate_signals_skipped");
    }

    @Scheduled(cron = "0 15 1 * * *")
    public void validateReferentialIntegrity() {
        log.debug("validation.referential_integrity_skipped");
    }

    @Scheduled(cron = "0 30 1 * * *")
    public void validateSignalCompleteness() {
        log.debug("validation.signal_completeness_skipped");
    }
}
