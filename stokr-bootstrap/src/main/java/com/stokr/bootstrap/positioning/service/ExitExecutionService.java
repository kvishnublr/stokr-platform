package com.stokr.bootstrap.positioning.service;

import com.stokr.common.messaging.events.ExitSignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles exit signal processing from automated exit rules.
 * This service consumes ExitSignalEvent from trading.exits queue
 * and executes exit orders based on stop loss, take profit, or other exit reasons.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExitExecutionService {

    public void processExitSignal(ExitSignalEvent event) {
        try {
            log.debug("Processing exit signal: {} | Order: {} | Reason: {}",
                    event.getExitSignalId(), event.getOrderId(), event.getReason());

            // Step 1: Validate exit signal
            validateExitSignalEvent(event);

            // Step 2: Determine exit type and handle accordingly
            switch (event.getReason()) {
                case "STOP_LOSS_HIT":
                    handleStopLossExit(event);
                    break;
                case "TAKE_PROFIT_HIT":
                    handleTakeProfitExit(event);
                    break;
                case "TRAILING_STOP_HIT":
                    handleTrailingStopExit(event);
                    break;
                case "MANUAL_EXIT":
                    handleManualExit(event);
                    break;
                case "MARKET_CLOSE":
                    handleMarketCloseExit(event);
                    break;
                default:
                    log.warn("Unknown exit reason: {}", event.getReason());
            }

            log.debug("Exit signal processed successfully: {}", event.getExitSignalId());

        } catch (Exception e) {
            log.error("Error processing exit signal: {}", event.getExitSignalId(), e);
            throw new RuntimeException("Exit signal processing failed", e);
        }
    }

    @Transactional
    private void handleStopLossExit(ExitSignalEvent event) {
        log.info("Stop loss triggered: Order {} | P&L: {} | Price: {}",
                event.getOrderId(),
                event.getPnlPercentage() != null ? String.format("%.2f%%", event.getPnlPercentage()) : "N/A",
                event.getCurrentMarketPrice());

        // TODO: Execute immediate exit order
        // 1. Find position by order ID
        // 2. Place market sell order for entire position
        // 3. Mark position as exited due to stop loss
        // 4. Record P&L and exit details
    }

    @Transactional
    private void handleTakeProfitExit(ExitSignalEvent event) {
        log.info("Take profit hit: Order {} | P&L: {} | Price: {}",
                event.getOrderId(),
                event.getPnlPercentage() != null ? String.format("%.2f%%", event.getPnlPercentage()) : "N/A",
                event.getExitTargetPrice());

        // TODO: Execute exit order
        // 1. Find position by order ID
        // 2. Place market sell order for entire position
        // 3. Mark position as exited due to take profit
        // 4. Record winning trade details
    }

    @Transactional
    private void handleTrailingStopExit(ExitSignalEvent event) {
        log.info("Trailing stop triggered: Order {} | P&L: {} | Price: {}",
                event.getOrderId(),
                event.getPnlPercentage() != null ? String.format("%.2f%%", event.getPnlPercentage()) : "N/A",
                event.getCurrentMarketPrice());

        // TODO: Execute exit order with trailing stop logic
        // 1. Find position by order ID
        // 2. Place market sell order
        // 3. Mark position as exited with trailing stop
    }

    @Transactional
    private void handleManualExit(ExitSignalEvent event) {
        log.info("Manual exit requested: Order {} | P&L: {}",
                event.getOrderId(),
                event.getPnlPercentage() != null ? String.format("%.2f%%", event.getPnlPercentage()) : "N/A");

        // TODO: Execute manual exit
        // 1. Find position by order ID
        // 2. Place market sell order
        // 3. Mark position as manually exited
    }

    @Transactional
    private void handleMarketCloseExit(ExitSignalEvent event) {
        log.info("Market close exit: Order {} | P&L: {} | Close Price: {}",
                event.getOrderId(),
                event.getPnlPercentage() != null ? String.format("%.2f%%", event.getPnlPercentage()) : "N/A",
                event.getExitPrice());

        // TODO: Execute end-of-day exit
        // 1. Find position by order ID
        // 2. Place market sell order at market close
        // 3. Mark position as closed at market close
    }

    private void validateExitSignalEvent(ExitSignalEvent event) {
        if (event.getExitSignalId() == null || event.getOrderId() == null) {
            throw new IllegalArgumentException("Invalid ExitSignalEvent: missing required fields");
        }
    }
}
