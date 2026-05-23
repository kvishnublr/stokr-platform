package com.stokr.execution.reconciliation;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationEngine {

    private final Map<String, FillRecord> paperFills = new HashMap<>();
    private final Map<String, FillRecord> brokerFills = new HashMap<>();
    private final List<ReconciliationReport> reports = new ArrayList<>();

    private ExecutionMode currentMode = ExecutionMode.PAPER;
    private static final double DRIFT_THRESHOLD_BPS = 50.0;  // 0.5% drift tolerance

    public void setCurrentMode(ExecutionMode mode) {
        this.currentMode = mode;
    }

    public void recordPaperFill(String orderId, BigDecimal price, BigDecimal quantity, Instant fillTime) {
        FillRecord record = FillRecord.builder()
                .orderId(orderId)
                .price(price)
                .quantity(quantity)
                .fillTime(fillTime)
                .source("PAPER")
                .timestamp(Instant.now())
                .build();

        paperFills.put(orderId, record);
        log.debug("reconciliation.paper_fill orderId={} price={} qty={}", orderId, price, quantity);
    }

    public void recordBrokerFill(String orderId, BigDecimal price, BigDecimal quantity, Instant fillTime) {
        FillRecord record = FillRecord.builder()
                .orderId(orderId)
                .price(price)
                .quantity(quantity)
                .fillTime(fillTime)
                .source("BROKER")
                .timestamp(Instant.now())
                .build();

        brokerFills.put(orderId, record);
        log.debug("reconciliation.broker_fill orderId={} price={} qty={}", orderId, price, quantity);
    }

    public ReconciliationReport reconcile() {
        ReconciliationReport report = new ReconciliationReport();
        report.setReconciledAt(Instant.now());
        report.setMode(currentMode.toString());

        if (currentMode == ExecutionMode.LIVE) {
            // In LIVE mode, broker fills should be primary; paper fills should be empty
            if (!paperFills.isEmpty()) {
                log.warn("reconciliation.unexpected_paper_fills count={}", paperFills.size());
                report.setStatus("WARNING");
                report.addIssue("Unexpected paper fills in LIVE mode: " + paperFills.size());
            } else {
                report.setStatus("OK");
            }
        } else if (currentMode == ExecutionMode.PAPER) {
            // In PAPER mode, paper fills should be primary; broker fills should be empty
            if (!brokerFills.isEmpty()) {
                log.error("reconciliation.broker_fills_in_paper_mode count={}", brokerFills.size());
                report.setStatus("FAILED");
                report.addIssue("CRITICAL: Broker fills detected in PAPER mode: " + brokerFills.size());
            } else {
                report.setStatus("OK");
            }
        } else if (currentMode == ExecutionMode.BOTH) {
            // In BOTH mode, compare paper vs broker fills for each order
            reconcileBothMode(report);
        }

        report.setPaperFillCount(paperFills.size());
        report.setBrokerFillCount(brokerFills.size());

        reports.add(report);
        return report;
    }

    private void reconcileBothMode(ReconciliationReport report) {
        report.setStatus("OK");

        // Find all unique order IDs
        java.util.Set<String> allOrderIds = new java.util.HashSet<>();
        allOrderIds.addAll(paperFills.keySet());
        allOrderIds.addAll(brokerFills.keySet());

        for (String orderId : allOrderIds) {
            FillRecord paperFill = paperFills.get(orderId);
            FillRecord brokerFill = brokerFills.get(orderId);

            if (paperFill == null || brokerFill == null) {
                // One side missing - expected in some scenarios
                continue;
            }

            // Compare prices and quantities
            double priceDrift = calculateDriftBps(paperFill.getPrice(), brokerFill.getPrice());
            double qtyDrift = calculateDriftBps(paperFill.getQuantity(), brokerFill.getQuantity());

            if (priceDrift > DRIFT_THRESHOLD_BPS) {
                log.warn("reconciliation.price_drift orderId={} paper={} broker={} drift_bps={}",
                        orderId, paperFill.getPrice(), brokerFill.getPrice(), priceDrift);
                report.setStatus("WARNING");
                report.addDivergence(orderId, "price_drift_bps=" + priceDrift);
            }

            if (qtyDrift > DRIFT_THRESHOLD_BPS) {
                log.warn("reconciliation.qty_drift orderId={} paper={} broker={} drift_bps={}",
                        orderId, paperFill.getQuantity(), brokerFill.getQuantity(), qtyDrift);
                report.setStatus("WARNING");
                report.addDivergence(orderId, "qty_drift_bps=" + qtyDrift);
            }

            log.debug("reconciliation.hybrid_match orderId={} paper_price={} broker_price={} drift_bps={}",
                    orderId, paperFill.getPrice(), brokerFill.getPrice(), priceDrift);
        }
    }

    private double calculateDriftBps(BigDecimal paper, BigDecimal broker) {
        if (broker.doubleValue() == 0) {
            return 0.0;
        }
        double drift = Math.abs(paper.doubleValue() - broker.doubleValue()) / broker.doubleValue();
        return drift * 10000.0;  // Convert to basis points
    }

    public void clearRecords() {
        paperFills.clear();
        brokerFills.clear();
        log.info("reconciliation.cleared");
    }

    public List<ReconciliationReport> getReports(int limit) {
        int start = Math.max(0, reports.size() - limit);
        return new ArrayList<>(reports.subList(start, reports.size()));
    }

    @Data
    @Builder
    public static class FillRecord {
        private String orderId;
        private BigDecimal price;
        private BigDecimal quantity;
        private Instant fillTime;
        private String source;  // PAPER or BROKER
        private Instant timestamp;
    }

    @Data
    public static class ReconciliationReport {
        private Instant reconciledAt;
        private String mode;
        private String status;
        private int paperFillCount;
        private int brokerFillCount;
        private List<String> issues = new ArrayList<>();
        private Map<String, List<String>> divergences = new HashMap<>();

        public void addIssue(String issue) {
            issues.add(issue);
        }

        public void addDivergence(String orderId, String detail) {
            divergences.computeIfAbsent(orderId, k -> new ArrayList<>()).add(detail);
        }
    }
}
