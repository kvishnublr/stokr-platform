package com.stokr.execution.comparison;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.execution.capital.StrategyCapitalReservationRepository;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationSafetyMonitorService {

    private static final List<OrderState> PENDING_STATES = List.of(
            OrderState.CREATED, OrderState.VALIDATED, OrderState.PENDING_SUBMISSION,
            OrderState.SUBMITTED, OrderState.ACCEPTED, OrderState.PARTIALLY_FILLED);

    private final ExecutionComparisonMetricsRepository comparisonRepository;
    private final OmsOrderRepository orderRepository;
    private final StrategyCapitalReservationRepository reservationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${stokr.reconciliation.stale-hours:4}")
    private long staleHours;

    @Scheduled(fixedDelayString = "${stokr.reconciliation.safety.poll-ms:300000}")
    @Transactional(readOnly = true)
    public void scheduledSafetyScan() {
        try {
            runSafetyScan();
        } catch (Exception ex) {
            log.warn("reconciliation.safety.scan_failed {}", ex.getMessage());
        }
    }

    public Map<String, Object> runSafetyScan() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(staleHours, ChronoUnit.HOURS);

        List<Map<String, Object>> alerts = new ArrayList<>();
        alerts.addAll(scanUnreconciled(staleBefore));
        alerts.addAll(scanOrphanLivePositions());
        alerts.addAll(scanStalePendingOrders(staleBefore));
        alerts.addAll(scanStaleReservations(staleBefore));

        for (Map<String, Object> alert : alerts) {
            log.error("reconciliation.safety.alert type={} detail={}", alert.get("type"), alert.get("detail"));
            eventPublisher.publishEvent(new OperationalRealtimeEvent("reconciliation_safety_alert", alert));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scannedAt", now.toString());
        summary.put("alertCount", alerts.size());
        summary.put("alerts", alerts);
        summary.put("unreconciledCount", comparisonRepository.countByReconciliationStatusAndDeletedFalse("AWAITING_CLOSE")
                + comparisonRepository.countByReconciliationStatusAndDeletedFalse("PENDING"));
        summary.put("failedCount", comparisonRepository.countByReconciliationStatusAndDeletedFalse("FAILED"));
        return summary;
    }

    private List<Map<String, Object>> scanUnreconciled(Instant staleBefore) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (ExecutionComparisonMetrics m : comparisonRepository.findStaleUnreconciled(staleBefore, PageRequest.of(0, 100))) {
            alerts.add(alert("UNRECONCILED_TRADE",
                    "signalId=" + m.getSignalId() + " strategy=" + m.getStrategyKey()
                            + " status=" + m.getReconciliationStatus()
                            + " paperClosed=" + m.isPaperPositionClosed()
                            + " liveClosed=" + m.isLivePositionClosed()));
        }
        return alerts;
    }

    private List<Map<String, Object>> scanOrphanLivePositions() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (ExecutionComparisonMetrics m : comparisonRepository.findStaleUnreconciled(
                Instant.now().minus(1, ChronoUnit.HOURS), PageRequest.of(0, 200))) {
            if (m.getLiveOrderId() != null && m.isPaperPositionClosed() && !m.isLivePositionClosed()) {
                alerts.add(alert("ORPHAN_LIVE_POSITION",
                        "signalId=" + m.getSignalId() + " strategy=" + m.getStrategyKey()
                                + " symbol=" + m.getSymbol() + " liveOrderId=" + m.getLiveOrderId()));
            }
            if (m.getLiveOrderId() != null && m.isLiveEntryFilled() && !m.isLivePositionClosed()
                    && m.getQuantityDrift() != null && m.getQuantityDrift().signum() > 0) {
                alerts.add(alert("QUANTITY_MISMATCH",
                        "signalId=" + m.getSignalId() + " drift=" + m.getQuantityDrift().toPlainString()));
            }
        }
        return alerts;
    }

    private List<Map<String, Object>> scanStalePendingOrders(Instant staleBefore) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        long count = orderRepository.countStuckOrders(PENDING_STATES, staleBefore);
        if (count > 0) {
            alerts.add(alert("STALE_PENDING_ORDERS", "count=" + count + " before=" + staleBefore));
        }
        return alerts;
    }

    private List<Map<String, Object>> scanStaleReservations(Instant staleBefore) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        long staleReservations = reservationRepository.findAll().stream()
                .filter(r -> !r.isDeleted())
                .filter(r -> "ACTIVE".equals(r.getStatus()))
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isBefore(staleBefore))
                .count();
        if (staleReservations > 0) {
            alerts.add(alert("STALE_RESERVATIONS", "count=" + staleReservations));
        }
        return alerts;
    }

    private static Map<String, Object> alert(String type, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("detail", detail);
        m.put("severity", "ERROR");
        return m;
    }
}
