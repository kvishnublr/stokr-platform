package com.stokr.execution.truth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Execution truth reconciliation scheduler.
 *
 * Periodically validates:
 * 1. Broker position truth vs internal ledger
 * 2. Execution fills vs orders
 * 3. Order-execution mapping integrity
 * 4. Isolation (PAPER ≠ broker, LIVE = broker)
 *
 * Runs every 5 minutes during trading hours, alerts on discrepancies.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final BrokerPositionTruthService brokerTruth;
    private final ExecutionReconciliationService executionReconciliation;

    @Value("${stokr.reconciliation.interval-ms:300000}")
    private long reconciliationIntervalMs;

    @Value("${stokr.risk.trading-window-start:09:25}")
    private String tradingWindowStart;

    @Value("${stokr.risk.trading-window-end:14:45}")
    private String tradingWindowEnd;

    /**
     * Periodic position reconciliation (every 5 minutes during market hours).
     * Detects external acquisitions, missing positions, quantity mismatches.
     */
    @Scheduled(fixedDelayString = "${stokr.reconciliation.interval-ms:300000}")
    public void reconcilePositions() {
        if (!isInTradingHours()) {
            return; // Skip outside market hours
        }

        try {
            PositionReconciliationReport report = brokerTruth.reconcile();

            if (report.hasDiscrepancies()) {
                log.warn("reconciliation.discrepancies external={} missing={} qty_mismatch={}",
                    report.getExternalPositions().size(),
                    report.getMissingPositions().size(),
                    report.getQtyMismatches().size());

                // Alert on external positions (possible account sharing or manual trades)
                for (PositionReconciliationReport.PositionGap gap : report.getExternalPositions()) {
                    log.error("ALERT: external_position symbol={} qty={} price={} time={}",
                        gap.getSymbol(), gap.getQuantity(), gap.getPrice(), Instant.now());
                }

                // Alert on missing positions (external exit - market closed position?)
                for (PositionReconciliationReport.PositionGap gap : report.getMissingPositions()) {
                    log.error("ALERT: missing_position symbol={} qty={} time={}",
                        gap.getSymbol(), gap.getQuantity(), Instant.now());
                }

                // Alert on quantity mismatches
                for (PositionReconciliationReport.QuantityMismatch mismatch : report.getQtyMismatches()) {
                    log.error("ALERT: qty_mismatch symbol={} internal={} broker={} time={}",
                        mismatch.getSymbol(), mismatch.getInternalQty(), mismatch.getBrokerQty(), Instant.now());
                }
            } else {
                log.debug("reconciliation.clean positions match broker");
            }

        } catch (Exception ex) {
            log.error("reconciliation.position_failed {}", ex.getMessage(), ex);
        }
    }

    /**
     * Execution metrics validation (every 5 minutes).
     * Checks fill rates, latencies, slippage, mode isolation.
     */
    @Scheduled(fixedDelayString = "${stokr.reconciliation.interval-ms:300000}")
    public void validateExecutions() {
        if (!isInTradingHours()) {
            return;
        }

        try {
            ExecutionReconciliationService.ExecutionReconciliationMetrics metrics =
                executionReconciliation.validateTodaysExecutions();

            // Check isolation violations (critical)
            if (metrics.getIsolationViolations() > 0) {
                log.error("CRITICAL: isolation_violations={} PAPER orders called broker!",
                    metrics.getIsolationViolations());
            }

            // Check fill rate (warn if < 95%)
            if (metrics.getFillRate() < 95.0) {
                log.warn("execution.fill_rate={} below target", String.format("%.1f%%", metrics.getFillRate()));
            }

            // Check latency (warn if > 5s average)
            if (metrics.getAverageLatencyMs() > 5000) {
                log.warn("execution.latency_avg_ms={} above threshold", metrics.getAverageLatencyMs());
            }

            // Check slippage (warn if > 10bps average)
            if (metrics.getAverageSlippageBps() > 10.0) {
                log.warn("execution.slippage_avg_bps={} above threshold",
                    String.format("%.2f", metrics.getAverageSlippageBps()));
            }

            log.info("execution.metrics filled={} latency={}ms slippage={}bps",
                metrics.getFilledCount(),
                metrics.getAverageLatencyMs(),
                String.format("%.2f", metrics.getAverageSlippageBps()));

        } catch (Exception ex) {
            log.error("reconciliation.execution_failed {}", ex.getMessage(), ex);
        }
    }

    /**
     * Detect and alert on stale unfilled orders.
     */
    @Scheduled(fixedDelayString = "${stokr.reconciliation.interval-ms:300000}")
    public void detectStaleOrders() {
        if (!isInTradingHours()) {
            return;
        }

        try {
            var staleOrders = executionReconciliation.detectStaleUnfilledOrders();

            if (!staleOrders.isEmpty()) {
                log.error("ALERT: stale_unfilled_orders count={}", staleOrders.size());
                for (var order : staleOrders) {
                    log.error("  stale_order id={} symbol={} age_minutes={} status={}",
                        order.getId(), order.getSymbol(),
                        java.time.temporal.ChronoUnit.MINUTES.between(order.getCreatedAt(), Instant.now()),
                        order.getStatus());
                }
            }

        } catch (Exception ex) {
            log.error("reconciliation.stale_orders_check_failed {}", ex.getMessage());
        }
    }

    /**
     * Validate execution mapping integrity.
     */
    @Scheduled(fixedDelayString = "${stokr.reconciliation.interval-ms:300000}")
    public void validateExecutionMapping() {
        try {
            ExecutionReconciliationService.ValidationResult result =
                executionReconciliation.validateExecutionMapping();

            if (!result.isValid()) {
                log.error("CRITICAL: execution_mapping_errors count={}", result.getErrors().size());
                for (String error : result.getErrors()) {
                    log.error("  {}", error);
                }
            }

        } catch (Exception ex) {
            log.error("reconciliation.mapping_validation_failed {}", ex.getMessage());
        }
    }

    private boolean isInTradingHours() {
        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime start = java.time.LocalTime.parse(tradingWindowStart);
        java.time.LocalTime end = java.time.LocalTime.parse(tradingWindowEnd);
        return !now.isBefore(start) && !now.isAfter(end);
    }
}
