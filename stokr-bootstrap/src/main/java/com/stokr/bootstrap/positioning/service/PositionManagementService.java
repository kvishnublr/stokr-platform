package com.stokr.bootstrap.positioning.service;

import com.stokr.common.messaging.events.OrderExecutedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles position state updates from order execution events.
 * This service consumes OrderExecutedEvent from trading.orders queue
 * and updates position records in the core trading database.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionManagementService {

    public void updatePositionFromOrderExecution(OrderExecutedEvent event) {
        try {
            log.debug("Updating position state for order: {} | Signal: {} | Symbol: {}",
                    event.getOrderId(), event.getSignalId(), event.getSymbol());

            // Step 1: Validate order execution event
            validateOrderExecutedEvent(event);

            // Step 2: Create or update position record
            if ("FILLED".equals(event.getStatus()) || "PARTIAL".equals(event.getStatus())) {
                updatePositionFromFill(event);
            } else if ("REJECTED".equals(event.getStatus())) {
                handleRejectedOrder(event);
            } else if ("CANCELLED".equals(event.getStatus())) {
                handleCancelledOrder(event);
            }

            log.debug("Position updated successfully for order: {}", event.getOrderId());

        } catch (Exception e) {
            log.error("Error updating position from order execution: {}", event.getOrderId(), e);
            throw new RuntimeException("Position update failed", e);
        }
    }

    @Transactional
    private void updatePositionFromFill(OrderExecutedEvent event) {
        log.info("Registering position fill: Order {} | {} {} @ {} qty",
                event.getOrderId(),
                event.getSide(),
                event.getSymbol(),
                event.getFilledQuantity());

        // TODO: Implement position record creation/update
        // This would interact with PositionRepository to:
        // 1. Find existing position for symbol or create new one
        // 2. Update quantity, entry price, status
        // 3. Associate with signal ID for tracking
        // 4. Set order ID reference for exit matching
    }

    private void handleRejectedOrder(OrderExecutedEvent event) {
        log.warn("Order rejected by risk check: {} | Reason: {}",
                event.getOrderId(), event.getRejectionReason());

        // TODO: Mark signal as failed in position tracking
        // This allows strategy to retry or move to next signal
    }

    private void handleCancelledOrder(OrderExecutedEvent event) {
        log.warn("Order cancelled: {} | Reason: {}", event.getOrderId(), event.getRejectionReason());

        // TODO: Update position state to cancelled
        // Trigger position cleanup if needed
    }

    private void validateOrderExecutedEvent(OrderExecutedEvent event) {
        if (event.getOrderId() == null || event.getSymbol() == null) {
            throw new IllegalArgumentException("Invalid OrderExecutedEvent: missing required fields");
        }
    }
}
