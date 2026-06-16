package com.stokr.trading.service;

import com.stokr.trading.domain.*;
import com.stokr.trading.dto.*;
import com.stokr.trading.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final SignalRepository signalRepository;
    private final StrategyInstanceRepository instanceRepository;

    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByUser(UUID userId) {
        return orderRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByInstance(UUID instanceId) {
        return orderRepository.findByInstanceIdAndDeletedFalseOrderByCreatedAtDesc(instanceId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(UUID orderId) {
        Order order = orderRepository.findByIdAndDeletedFalse(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        return toDto(order);
    }

    @Transactional
    public OrderDto createOrder(UUID userId, CreateOrderRequest request) {
        Order order = Order.builder()
                .instanceId(request.getInstanceId())
                .signalId(request.getSignalId())
                .userId(userId)
                .symbol(request.getSymbol().toUpperCase())
                .side(request.getSide())
                .orderType(request.getOrderType() != null ? request.getOrderType() : "MARKET")
                .quantity(request.getQuantity())
                .price(request.getPrice())
                .triggerPrice(request.getTriggerPrice())
                .exchange(request.getExchange() != null ? request.getExchange() : "NSE")
                .productType(request.getProductType() != null ? request.getProductType() : "MIS")
                .status("PENDING")
                .build();

        Order saved = orderRepository.save(order);
        log.info("Order created: {} {} {}x{} for user: {}", 
                saved.getSide(), saved.getSymbol(), saved.getQuantity(), saved.getPrice(), userId);

        return toDto(saved);
    }

    @Transactional
    public OrderDto submitOrder(UUID orderId) {
        Order order = orderRepository.findByIdAndDeletedFalse(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        order.setStatus("SUBMITTED");
        order.setSubmittedAt(Instant.now());

        Order saved = orderRepository.save(order);
        log.info("Order submitted to broker: {}", orderId);

        return toDto(saved);
    }

    @Transactional
    public OrderDto fillOrder(UUID orderId, BigDecimal filledQty, BigDecimal avgPrice) {
        Order order = orderRepository.findByIdAndDeletedFalse(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        order.setStatus("FILLED");
        order.setFilledQuantity(filledQty);
        order.setAveragePrice(avgPrice);
        order.setFilledAt(Instant.now());

        Order saved = orderRepository.save(order);

        // Update position
        updatePositionFromFill(saved);

        // Update signal
        if (order.getSignalId() != null) {
            signalRepository.findById(order.getSignalId()).ifPresent(signal -> {
                signal.setStatus("EXECUTED");
                signal.setExecutedAt(Instant.now());
                signalRepository.save(signal);
            });
        }

        log.info("Order filled: {} {} {}x{}", orderId, saved.getSide(), filledQty, avgPrice);

        return toDto(saved);
    }

    @Transactional
    public OrderDto cancelOrder(UUID orderId, UUID userId) {
        Order order = orderRepository.findByIdAndDeletedFalse(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to cancel this order");
        }

        if (!"PENDING".equalsIgnoreCase(order.getStatus()) && !"SUBMITTED".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalArgumentException("Cannot cancel order in status: " + order.getStatus());
        }

        order.setStatus("CANCELLED");
        order.setCancelledAt(Instant.now());

        Order saved = orderRepository.save(order);

        // Update signal
        if (order.getSignalId() != null) {
            signalRepository.findById(order.getSignalId()).ifPresent(signal -> {
                signal.setStatus("SKIPPED");
                signal.setSkippedAt(Instant.now());
                signalRepository.save(signal);
            });
        }

        log.info("Order cancelled: {} by user: {}", orderId, userId);

        return toDto(saved);
    }

    @Transactional
    public OrderDto rejectOrder(UUID orderId, String reason) {
        Order order = orderRepository.findByIdAndDeletedFalse(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        order.setStatus("REJECTED");
        order.setRejectedAt(Instant.now());
        order.setRejectionReason(reason);

        Order saved = orderRepository.save(order);

        log.info("Order rejected: {} reason: {}", orderId, reason);

        return toDto(saved);
    }

    private void updatePositionFromFill(Order order) {
        if (order.getInstanceId() == null) return;

        Position position = positionRepository
                .findByInstanceIdAndSymbolAndStatusAndDeletedFalse(order.getInstanceId(), order.getSymbol(), "OPEN")
                .orElse(null);

        if (position == null) {
            // Create new position
            position = Position.builder()
                    .instanceId(order.getInstanceId())
                    .userId(order.getUserId())
                    .symbol(order.getSymbol())
                    .side(order.isBuy() ? "LONG" : "SHORT")
                    .quantity(order.getFilledQuantity())
                    .avgPrice(order.getAveragePrice())
                    .currentPrice(order.getAveragePrice())
                    .pnl(BigDecimal.ZERO)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .status("OPEN")
                    .exchange(order.getExchange())
                    .productType(order.getProductType())
                    .build();
        } else {
            // Update existing position
            BigDecimal existingQty = position.getQuantity();
            BigDecimal newQty = order.getFilledQuantity();
            BigDecimal existingAvg = position.getAvgPrice();
            BigDecimal newPrice = order.getAveragePrice();

            if (order.isBuy()) {
                // Adding to position
                BigDecimal totalQty = existingQty.add(newQty);
                BigDecimal totalValue = existingQty.multiply(existingAvg).add(newQty.multiply(newPrice));
                position.setQuantity(totalQty);
                position.setAvgPrice(totalValue.divide(totalQty, 4, java.math.RoundingMode.HALF_UP));
            } else {
                // Reducing position
                BigDecimal remainingQty = existingQty.subtract(newQty);
                if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    // Position closed
                    position.setStatus("CLOSED");
                    position.setClosedAt(Instant.now());
                    position.setRealizedPnl(calculatePnl(position));
                } else {
                    position.setQuantity(remainingQty);
                }
            }
        }

        position.setCurrentPrice(order.getAveragePrice());
        positionRepository.save(position);
    }

    private BigDecimal calculatePnl(Position position) {
        if (position.getAvgPrice() == null || position.getCurrentPrice() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = position.getCurrentPrice().subtract(position.getAvgPrice());
        if ("SHORT".equalsIgnoreCase(position.getSide())) {
            diff = diff.negate();
        }
        return diff.multiply(position.getQuantity());
    }

    private OrderDto toDto(Order o) {
        return OrderDto.builder()
                .id(o.getId())
                .instanceId(o.getInstanceId())
                .signalId(o.getSignalId())
                .userId(o.getUserId())
                .symbol(o.getSymbol())
                .side(o.getSide())
                .orderType(o.getOrderType())
                .quantity(o.getQuantity())
                .price(o.getPrice())
                .filledQuantity(o.getFilledQuantity())
                .averagePrice(o.getAveragePrice())
                .status(o.getStatus())
                .brokerOrderId(o.getBrokerOrderId())
                .exchange(o.getExchange())
                .productType(o.getProductType())
                .orderValue(o.getOrderValue())
                .createdAt(o.getCreatedAt())
                .filledAt(o.getFilledAt())
                .cancelledAt(o.getCancelledAt())
                .rejectedAt(o.getRejectedAt())
                .rejectionReason(o.getRejectionReason())
                .build();
    }
}
