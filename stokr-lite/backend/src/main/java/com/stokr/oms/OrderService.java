package com.stokr.oms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionService positionService;

    @Transactional
    public Order createOrder(Long deploymentId, String symbol, String side,
                              int quantity, BigDecimal price, String orderType) {
        Order order = Order.builder()
                .deploymentId(deploymentId)
                .symbol(symbol)
                .side(side)
                .quantity(quantity)
                .price(price)
                .orderType(orderType)
                .status(Order.PENDING)
                .build();
        return orderRepository.save(order);
    }

    @Transactional
    public Order completeOrder(Order order, String brokerOrderId, BigDecimal fillPrice, int fillQty) {
        order.setStatus(Order.COMPLETE);
        order.setBrokerOrderId(brokerOrderId);
        orderRepository.save(order);

        // Record trade
        Trade trade = Trade.builder()
                .orderId(order.getId())
                .fillPrice(fillPrice)
                .fillQuantity(fillQty)
                .build();
        tradeRepository.save(trade);

        // Update position
        positionService.updatePosition(order.getDeploymentId(), order.getSymbol(),
                order.getSide(), fillQty, fillPrice);

        log.info("Order {} completed: {} {} {} @ {}",
                order.getId(), order.getSide(), order.getSymbol(), fillQty, fillPrice);
        return order;
    }

    @Transactional
    public Order rejectOrder(Order order, String reason) {
        order.setStatus(Order.REJECTED);
        order.setErrorMessage(reason);
        return orderRepository.save(order);
    }

    @Transactional
    public Order cancelOrder(Order order) {
        order.setStatus(Order.CANCELLED);
        return orderRepository.save(order);
    }

    public Page<Order> getOrders(Long deploymentId, Pageable pageable) {
        return orderRepository.findByDeploymentId(deploymentId, pageable);
    }

    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }
}
