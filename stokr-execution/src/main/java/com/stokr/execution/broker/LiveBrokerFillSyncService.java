package com.stokr.execution.broker;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OmsTrade;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.oms.service.ExecutionLedgerService;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.oms.trace.ExecutionTraceService;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Polls Zerodha order book and reconciles LIVE platform orders stuck at ACCEPTED into FILLED/FAILED.
 * Verifies broker state before mutating OMS — never closes positions without a matching Kite order row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveBrokerFillSyncService {

    private static final Collection<OrderState> SYNC_STATES = List.of(
            OrderState.SUBMITTED, OrderState.ACCEPTED, OrderState.PARTIALLY_FILLED);

    private final OmsOrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final ExecutionLedgerService executionLedgerService;
    private final OmsTradeRepository tradeRepository;
    private final PortfolioAccountingService portfolioAccountingService;
    private final ExecutionTraceService executionTraceService;
    private final ApplicationEventPublisher eventPublisher;
    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;

    @Value("${stokr.execution.broker-fill-sync.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${stokr.execution.broker-fill-sync.interval-ms:15000}")
    public void scheduledSync() {
        if (!enabled) {
            return;
        }
        try {
            int updated = syncAll();
            if (updated > 0) {
                log.info("broker.fill_sync.cycle_done updated={}", updated);
            }
        } catch (Exception ex) {
            log.warn("broker.fill_sync.cycle_failed {}", ex.toString());
        }
    }

    @Transactional
    public int syncAll() {
        List<OmsOrder> pending = orderRepository.findAllLiveActiveOrders(SYNC_STATES).stream()
                .filter(o -> !o.isTestTrade())
                .filter(o -> o.getBrokerExternalOrderId() != null && !o.getBrokerExternalOrderId().isBlank())
                .filter(o -> "ZERODHA".equalsIgnoreCase(o.getBrokerVendor()))
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }

        Map<UUID, List<OmsOrder>> byUser = pending.stream().collect(Collectors.groupingBy(OmsOrder::getUserId));
        int updated = 0;
        for (var entry : byUser.entrySet()) {
            UUID userId = entry.getKey();
            List<ZerodhaBrokerOperationsService.BrokerOpenOrderDto> kiteOrders;
            try {
                kiteOrders = zerodhaBrokerOperationsService.recentOrders(userId, 300);
            } catch (Exception ex) {
                log.debug("broker.fill_sync.kite_fetch_failed user={} {}", userId, ex.getMessage());
                continue;
            }
            Map<String, ZerodhaBrokerOperationsService.BrokerOpenOrderDto> byKiteId = kiteOrders.stream()
                    .filter(k -> k.orderId() != null)
                    .collect(Collectors.toMap(ZerodhaBrokerOperationsService.BrokerOpenOrderDto::orderId, k -> k, (a, b) -> a));

            for (OmsOrder order : entry.getValue()) {
                ZerodhaBrokerOperationsService.BrokerOpenOrderDto kite = byKiteId.get(order.getBrokerExternalOrderId());
                if (kite == null) {
                    continue;
                }
                if (applyBrokerStatus(order, kite)) {
                    updated++;
                }
            }
        }
        return updated;
    }

    private boolean applyBrokerStatus(OmsOrder order, ZerodhaBrokerOperationsService.BrokerOpenOrderDto kite) {
        String status = kite.status() != null ? kite.status().trim().toUpperCase() : "";
        if (status.isBlank()) {
            return false;
        }
        if (status.equals("COMPLETE") || status.equals("COMPLETED")) {
            return applyFill(order, kite);
        }
        if (status.equals("REJECTED") || status.equals("CANCELLED") || status.equals("CANCELED")) {
            orderLifecycleService.transition(order.getId(), OrderState.FAILED,
                    "BROKER_" + status + (kite.statusMessage() != null ? ": " + kite.statusMessage() : ""));
            executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                    "phase", "BROKER_SYNC",
                    "kiteStatus", status
            ));
            publishOrderUpdate(order, OrderState.FAILED.name());
            return true;
        }
        return false;
    }

    private boolean applyFill(OmsOrder order, ZerodhaBrokerOperationsService.BrokerOpenOrderDto kite) {
        if (order.getState() == OrderState.FILLED) {
            return false;
        }
        BigDecimal qty = order.getQuantity();
        if (kite.filledQuantity() != null && kite.filledQuantity() > 0) {
            qty = BigDecimal.valueOf(kite.filledQuantity());
        }
        BigDecimal price = order.getLimitPrice() != null ? order.getLimitPrice() : BigDecimal.ONE;
        if (kite.averagePrice() != null && kite.averagePrice() > 0) {
            price = BigDecimal.valueOf(kite.averagePrice());
        }

        var ex = executionLedgerService.appendExecution(
                order,
                "kite-" + order.getBrokerExternalOrderId(),
                qty,
                price,
                "LIVE_BROKER",
                Instant.now(),
                null,
                null,
                null,
                price,
                "LIVE",
                null
        );

        OmsTrade tr = new OmsTrade();
        tr.setOrder(order);
        tr.setExecution(ex);
        tr.setQuantity(qty);
        tr.setPrice(price);
        tradeRepository.save(tr);

        OmsOrder filled = orderLifecycleService.transition(order.getId(), OrderState.FILLED, null);
        executionTraceService.trace(filled, ExecutionEventType.ORDER_FILLED, Map.of(
                "channel", "BROKER_SYNC",
                "kiteOrderId", order.getBrokerExternalOrderId()
        ));
        portfolioAccountingService.applyFill(filled.getUserId(), filled.getSymbol(), filled.getStrategyKey());
        publishOrderUpdate(filled, OrderState.FILLED.name());
        eventPublisher.publishEvent(new OperationalRealtimeEvent("broker_fill_synced", Map.of(
                "orderId", filled.getId().toString(),
                "userId", filled.getUserId().toString(),
                "symbol", filled.getSymbol() != null ? filled.getSymbol() : "",
                "kiteOrderId", filled.getBrokerExternalOrderId() != null ? filled.getBrokerExternalOrderId() : ""
        )));
        log.info("broker.fill_sync.filled orderId={} kiteOrderId={} qty={} price={}",
                filled.getId(), filled.getBrokerExternalOrderId(), qty, price);
        return true;
    }

    private void publishOrderUpdate(OmsOrder order, String state) {
        eventPublisher.publishEvent(new RealtimeBridgeEvents.OrderUpdate(
                order.getUserId(),
                order.getId(),
                order.getSymbol(),
                state,
                Instant.now()
        ));
    }
}
