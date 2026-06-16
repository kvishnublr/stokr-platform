package com.stokr.engine;

import com.stokr.broker.BrokerAdapter;
import com.stokr.broker.BrokerService;
import com.stokr.oms.*;
import com.stokr.risk.ErrorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRecoveryService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final BrokerService brokerService;
    private final ErrorLogService errorLogService;

    /**
     * Check and recover pending orders - verify status with broker.
     */
    public void recoverPendingOrders() {
        List<Order> pendingOrders = orderRepository.findByStatus(Order.PENDING);
        if (pendingOrders.isEmpty()) return;

        log.info("Recovering {} pending orders", pendingOrders.size());

        for (Order order : pendingOrders) {
            try {
                if (order.getBrokerOrderId() != null) {
                    // Has broker order ID - check status
                    log.info("Checking status of order {} (broker: {})",
                            order.getId(), order.getBrokerOrderId());
                    // TODO: Query broker for actual status and update accordingly
                } else {
                    // No broker order ID - may have failed to send
                    log.warn("Order {} has no broker order ID - may need manual intervention",
                            order.getId());
                    errorLogService.logError(order.getDeploymentId(), "ORPHAN_ORDER",
                            "Order has no broker order ID", null, "WARN");
                }
            } catch (Exception e) {
                log.error("Error recovering order {}", order.getId(), e);
            }
        }
    }
}
