package com.stokr.execution.alert;

import com.stokr.common.events.OrderStateTransitionEvent;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Routes OMS order-state transitions into the execution alert log so LIVE failures are
 * visible to the admin the moment they happen. Before this bridge existed the alert
 * hooks (BROKER_REJECTED / ORDER_REJECTED / LIVE_FILL) were never invoked ??? a broker
 * rejection during market hours produced no alert row, no Telegram, no UI signal.
 *
 * CANCELLED is intentionally not alerted: position-sweep cleanup cancels orders in bulk
 * and would drown real failures.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderStateAlertBridge {

    private final OmsOrderRepository omsOrderRepository;
    private final ExecutionAlertService executionAlertService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderStateTransition(OrderStateTransitionEvent event) {
        if (event == null || !"LIVE".equals(event.executionMode())) {
            return;
        }
        try {
            OmsOrder order = omsOrderRepository.findById(event.orderId())
                    .filter(o -> !o.isDeleted())
                    .orElse(null);
            if (order == null) {
                return;
            }
            switch (event.newState()) {
                case "FILLED", "PARTIALLY_FILLED" ->
                        executionAlertService.onLiveFill(order, order.getLimitPrice());
                case "REJECTED", "FAILED" -> {
                    String reason = event.rejectReason() != null ? event.rejectReason() : "unknown";
                    if (reason.startsWith("BROKER_REJECTED")) {
                        executionAlertService.onLiveBrokerRejected(order, reason);
                    } else {
                        executionAlertService.onLiveOrderRejected(order, reason);
                    }
                }
                default -> { /* CANCELLED and others: no alert */ }
            }
        } catch (Exception ex) {
            log.warn("alert.bridge_failed orderId={} state={} err={}",
                    event.orderId(), event.newState(), ex.getMessage());
        }
    }
}
