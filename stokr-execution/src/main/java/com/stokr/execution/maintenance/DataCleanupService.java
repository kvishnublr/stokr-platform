package com.stokr.execution.maintenance;

import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PHASE 4: Data cleanup and validation.
 *
 * Maintains database integrity by:
 * - Archiving old signals (> 30 days)
 * - Cleaning orphaned orders/fills
 * - Removing duplicate signals
 * - Validating referential integrity
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataCleanupService {

    private final StrategySignalRepository signalRepository;
    private final OmsOrderRepository orderRepository;
    private final OmsExecutionRepository executionRepository;

    @Value("${stokr.cleanup.archive-days:30}")
    private int archiveDays;

    /**
     * Archive old signals daily at 00:30.
     */
    @Scheduled(cron = "0 30 0 * * *")
    @Transactional
    public void archiveOldSignals() {
        try {
            Instant cutoff = Instant.now().minus(archiveDays, ChronoUnit.DAYS);
            long archivedCount = signalRepository.markAsArchivedBefore(cutoff);
            log.info("cleanup.signals_archived count={} cutoff_days={}", archivedCount, archiveDays);
        } catch (Exception ex) {
            log.error("cleanup.archive_signals_failed {}", ex.getMessage());
        }
    }

    /**
     * Clean orphaned orders (orders with no executions > 24h old).
     */
    @Scheduled(cron = "0 45 0 * * *")
    @Transactional
    public void cleanOrphanedOrders() {
        try {
            Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
            long deletedCount = orderRepository.deleteOrphanedOrdersBefore(oneDayAgo);
            log.info("cleanup.orphaned_orders_deleted count={}", deletedCount);
        } catch (Exception ex) {
            log.error("cleanup.orphaned_orders_failed {}", ex.getMessage());
        }
    }

    /**
     * Detect and remove duplicate signals (same strategy+symbol within 5 minutes).
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void removeDuplicateSignals() {
        try {
            long duplicatesRemoved = signalRepository.removeDuplicatesWithinWindow(
                java.time.Duration.ofMinutes(5)
            );
            log.info("cleanup.duplicate_signals_removed count={}", duplicatesRemoved);
        } catch (Exception ex) {
            log.error("cleanup.remove_duplicates_failed {}", ex.getMessage());
        }
    }

    /**
     * Validate referential integrity: all executions reference valid orders.
     */
    @Scheduled(cron = "0 15 1 * * *")
    public void validateReferentialIntegrity() {
        try {
            long orphanedExecutions = executionRepository.countOrphanedExecutions();
            if (orphanedExecutions > 0) {
                log.error("CRITICAL: orphaned_executions count={}", orphanedExecutions);
                executionRepository.deleteOrphanedExecutions();
            } else {
                log.info("validation.referential_integrity clean");
            }
        } catch (Exception ex) {
            log.error("cleanup.referential_integrity_check_failed {}", ex.getMessage());
        }
    }

    /**
     * Validate signal data completeness.
     */
    @Scheduled(cron = "0 30 1 * * *")
    public void validateSignalCompleteness() {
        try {
            // Check for signals missing critical fields
            long incompleteSignals = signalRepository.countIncompleteSignals();
            if (incompleteSignals > 0) {
                log.warn("validation.incomplete_signals count={}", incompleteSignals);
            } else {
                log.info("validation.signal_completeness clean");
            }
        } catch (Exception ex) {
            log.error("cleanup.signal_validation_failed {}", ex.getMessage());
        }
    }
}
