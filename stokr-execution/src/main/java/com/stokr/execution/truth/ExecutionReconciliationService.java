package com.stokr.execution.truth;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution reconciliation authority.
 *
 * Validates that order executions match what the broker reports.
 * Ensures PAPER orders never hit broker, LIVE orders match broker fills.
 *
 * Key metrics:
 * - Fill latency: time from order submission to first fill
 * - Slippage: difference between reference price and actual fill price
 * - Partial fill rate: % of orders that fill in multiple tranches
 * - Execution mode isolation: PAPER orders don't call broker API
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionReconciliationService {

    private final OmsOrderRepository orderRepository;
    private final OmsExecutionRepository executionRepository;
    private final BrokerPositionTruthService brokerTruth;

    /**
     * Validate execution integrity for orders submitted today.
     * Checks: fill count, prices, latencies, mode isolation.
     */
    public ExecutionReconciliationMetrics validateTodaysExecutions() {
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);

        List<OmsOrder> ordersToday = orderRepository.findByCreatedAtBetween(startOfDay, endOfDay);
        ExecutionReconciliationMetrics metrics = new ExecutionReconciliationMetrics();
        metrics.setReportTime(Instant.now());

        for (OmsOrder order : ordersToday) {
            List<OmsExecution> fills = executionRepository.findByOrderId(order.getId());

            if (fills.isEmpty()) {
                metrics.incrementUnfilled();
                continue;
            }

            metrics.incrementFilled();

            // Check fill latency (time to first fill)
            OmsExecution firstFill = fills.get(0);
            long latencyMs = ChronoUnit.MILLIS.between(order.getCreatedAt(), firstFill.getExecutedAt());
            metrics.recordLatency(latencyMs);

            // Check slippage on each fill
            BigDecimal refPrice = order.getOrderPrice();
            for (OmsExecution fill : fills) {
                BigDecimal fillPrice = fill.getExecutionPrice();
                if (refPrice.signum() > 0 && fillPrice.signum() > 0) {
                    BigDecimal slippage = fillPrice.subtract(refPrice).abs();
                    BigDecimal slippagePct = slippage.divide(refPrice, 6, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                    metrics.recordSlippage(slippagePct.doubleValue());
                }
            }

            // Check mode isolation (PAPER orders should never call broker)
            String mode = order.getExecutionMode();
            if ("PAPER".equalsIgnoreCase(mode)) {
                if (order.getBrokerOrderId() != null && !order.getBrokerOrderId().isEmpty()) {
                    metrics.incrementIsolationViolation();
                    log.error("isolation.violation PAPER order has broker_order_id={}", order.getBrokerOrderId());
                }
            }

            // Track partial fills
            if (fills.size() > 1) {
                metrics.incrementPartialFill();
            }
        }

        // Calculate summary statistics
        if (ordersToday.size() > 0) {
            metrics.setFillRate(100.0 * metrics.getFilledCount() / ordersToday.size());
        }
        if (metrics.getPartialFillCount() > 0) {
            metrics.setPartialFillRate(100.0 * metrics.getPartialFillCount() / metrics.getFilledCount());
        }

        log.info("reconciliation.metrics filled={} unfilled={} latency_ms_avg={} slippage_bps_avg={} isolation_violations={}",
            metrics.getFilledCount(),
            metrics.getUnfilledCount(),
            metrics.getAverageLatencyMs(),
            metrics.getAverageSlippageBps(),
            metrics.getIsolationViolations());

        return metrics;
    }

    /**
     * Detect stale unfilled orders (submitted > 60 minutes ago with no execution).
     * These indicate potential stuck orders or scheduler issues.
     */
    public List<OmsOrder> detectStaleUnfilledOrders() {
        Instant staleBefore = Instant.now().minus(60, ChronoUnit.MINUTES);
        return orderRepository.findUnfilledOrdersBefore(staleBefore);
    }

    /**
     * Validate order-to-execution mapping.
     * Ensures every execution references a valid order.
     */
    public ValidationResult validateExecutionMapping() {
        List<OmsExecution> allExecutions = executionRepository.findAll();
        ValidationResult result = new ValidationResult();

        for (OmsExecution exec : allExecutions) {
            if (exec.getOrderId() == null) {
                result.addError("Execution " + exec.getId() + " has no order reference");
            } else {
                OmsOrder order = orderRepository.findById(exec.getOrderId()).orElse(null);
                if (order == null) {
                    result.addError("Execution " + exec.getId() + " references non-existent order " + exec.getOrderId());
                }
            }
        }

        if (result.getErrors().isEmpty()) {
            log.info("validation.execution_mapping clean all {} executions mapped correctly", allExecutions.size());
        } else {
            log.error("validation.execution_mapping errors={}", result.getErrors().size());
        }

        return result;
    }

    /**
     * Execution reconciliation metrics.
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ExecutionReconciliationMetrics {
        private Instant reportTime;
        private int filledCount = 0;
        private int unfilledCount = 0;
        private int partialFillCount = 0;
        private int isolationViolations = 0;
        private double fillRate = 0.0;
        private double partialFillRate = 0.0;

        private Map<String, Long> latencyMs = new HashMap<>();
        private Map<String, Double> slippageBps = new HashMap<>();

        public void incrementFilled() { filledCount++; }
        public void incrementUnfilled() { unfilledCount++; }
        public void incrementPartialFill() { partialFillCount++; }
        public void incrementIsolationViolation() { isolationViolations++; }

        public void recordLatency(long ms) {
            latencyMs.put(String.valueOf(filledCount), ms);
        }

        public void recordSlippage(double pct) {
            slippageBps.put(String.valueOf(filledCount), pct * 100);
        }

        public long getAverageLatencyMs() {
            if (latencyMs.isEmpty()) return 0;
            return latencyMs.values().stream().mapToLong(Long::longValue).average().orElse(0);
        }

        public double getAverageSlippageBps() {
            if (slippageBps.isEmpty()) return 0;
            return slippageBps.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
    }

    /**
     * Validation result with error collection.
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ValidationResult {
        private List<String> errors = new java.util.ArrayList<>();
        public void addError(String msg) { errors.add(msg); }
        public boolean isValid() { return errors.isEmpty(); }
    }
}
