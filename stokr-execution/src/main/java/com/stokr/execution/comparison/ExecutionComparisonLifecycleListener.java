package com.stokr.execution.comparison;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.strategy.lifecycle.ExitCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionComparisonLifecycleListener {

    private static final Set<String> TERMINAL_OUTCOMES = Set.of(
            "TARGET_HIT", "STOPLOSS_HIT", "SL_HIT", "BREAKEVEN_EXIT", "EXPIRED",
            "PRESSURE_EXIT", "TIME_EXIT", "FEED_PROTECTION", "LIQUIDITY_PROTECTION", "MANUAL");

    private final TradeLifecycleReconciliationService reconciliationService;
    private final OmsOrderRepository orderRepository;
    private final OmsTradeRepository tradeRepository;

    @EventListener
    @Transactional
    public void onOperationalEvent(OperationalRealtimeEvent event) {
        if (event == null || event.topic() == null) {
            return;
        }
        switch (event.topic()) {
            case "execution_fill_complete" -> handleExecutionFillComplete(event.payload());
            case "broker_fill_synced" -> handleBrokerFillSynced(event.payload());
            case "signal_outcome" -> handleSignalOutcome(event.payload());
            default -> { }
        }
    }

    private void handleExecutionFillComplete(Map<String, Object> payload) {
        UUID orderId = parseUuid(payload.get("orderId"));
        if (orderId == null) {
            return;
        }
        OmsOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.isDeleted()) {
            return;
        }
        int fills = intVal(payload.get("fills"), reconciliationService.countFillLegs(order));
        BigDecimal price = resolveAvgFillPrice(order);
        long latency = order.getCreatedAt() != null
                ? Math.max(0, java.time.Duration.between(order.getCreatedAt(), java.time.Instant.now()).toMillis())
                : 0L;
        reconciliationService.onOrderFilled(order, price, fills, latency);
    }

    private void handleBrokerFillSynced(Map<String, Object> payload) {
        UUID orderId = parseUuid(payload.get("orderId"));
        if (orderId == null) {
            return;
        }
        OmsOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.isDeleted()) {
            return;
        }
        BigDecimal price = resolveAvgFillPrice(order);
        int fills = reconciliationService.countFillLegs(order);
        long latency = order.getCreatedAt() != null
                ? Math.max(0, java.time.Duration.between(order.getCreatedAt(), java.time.Instant.now()).toMillis())
                : 0L;
        reconciliationService.onOrderFilled(order, price, Math.max(1, fills), latency);
    }

    private void handleSignalOutcome(Map<String, Object> payload) {
        String outcomeStatus = stringVal(payload.get("outcomeStatus"));
        if (outcomeStatus == null || !TERMINAL_OUTCOMES.contains(outcomeStatus.toUpperCase())) {
            if (outcomeStatus == null || !ExitCategory.isTerminalOutcome(outcomeStatus)) {
                return;
            }
        }
        UUID signalId = parseUuid(payload.get("signalId"));
        if (signalId == null) {
            return;
        }
        reconciliationService.onPaperPositionClosed(signalId);
    }

    private BigDecimal resolveAvgFillPrice(OmsOrder order) {
        var trades = tradeRepository.findByOrder_IdOrderByCreatedAtAsc(order.getId());
        if (trades.isEmpty()) {
            if (order.getEntryReferencePrice() != null) {
                return order.getEntryReferencePrice();
            }
            return order.getLimitPrice();
        }
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        for (var tr : trades) {
            if (tr.getPrice() != null && tr.getQuantity() != null) {
                notional = notional.add(tr.getPrice().multiply(tr.getQuantity()));
                qty = qty.add(tr.getQuantity());
            }
        }
        if (qty.signum() <= 0) {
            return order.getEntryReferencePrice();
        }
        return notional.divide(qty, 8, java.math.RoundingMode.HALF_UP);
    }

    private static UUID parseUuid(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intVal(Object v, int fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
