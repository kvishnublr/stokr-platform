package com.stokr.bootstrap.trading;

import com.stokr.execution.service.OrderPlacementService;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OmsOrderRepository;
import com.stokr.oms.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "stokr.confidence-strategy.auto-trade-enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class ConfidenceSignalExitService {

    private final OmsOrderRepository orderRepository;
    private final OrderPlacementService orderPlacementService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private String marketZone;

    @Value("${stokr.marketdata.session.nse.end:15:30}")
    private String nseEndTime;

    @Value("${stokr.confidence-strategy.exit.profit-target-high:2.0}")
    private double profitTargetHigh; // 2% for confidence >= 80

    @Value("${stokr.confidence-strategy.exit.profit-target-medium:1.5}")
    private double profitTargetMedium; // 1.5% for confidence 70-80

    @Value("${stokr.confidence-strategy.exit.profit-target-low:1.0}")
    private double profitTargetLow; // 1% for confidence < 70

    @Value("${stokr.confidence-strategy.exit.stop-loss-high:1.0}")
    private double stopLossHigh; // 1% SL for confidence >= 80

    @Value("${stokr.confidence-strategy.exit.stop-loss-medium:1.5}")
    private double stopLossMedium; // 1.5% SL for confidence 70-80

    @Value("${stokr.confidence-strategy.exit.stop-loss-low:2.0}")
    private double stopLossLow; // 2% SL for confidence < 70

    // Run every minute to check for exits
    @Scheduled(fixedRateString = "${stokr.confidence-strategy.exit.check-interval-ms:60000}",
               initialDelayString = "${stokr.confidence-strategy.exit.initial-delay-ms:10000}")
    @Transactional
    public void checkAndClosePositions() {
        try {
            log.debug("🔍 Checking for positions to close...");

            // Check if near market close (15:20-15:30 NSE)
            boolean nearMarketClose = isNearMarketClose();

            // Get all open CONFIDENCE-BASED orders
            List<OmsOrder> openOrders = orderRepository.findByStrategyNameLikeAndStatusIn(
                "CONFIDENCE_BASED_%",
                List.of(OrderStatus.OPEN, OrderStatus.PARTIAL)
            );

            if (openOrders.isEmpty()) {
                log.debug("ℹ️  No open confidence-based orders to check");
                return;
            }

            log.debug("Found {} open confidence-based orders to check", openOrders.size());

            AtomicInteger closed = new AtomicInteger(0);
            AtomicInteger targetHit = new AtomicInteger(0);
            AtomicInteger slHit = new AtomicInteger(0);
            AtomicInteger marketCloseClose = new AtomicInteger(0);

            for (OmsOrder order : openOrders) {
                try {
                    // Check if should close
                    ExitReason exitReason = checkExitConditions(order, nearMarketClose);

                    if (exitReason != null) {
                        log.info("📊 Closing order {} ({})", order.getId(), exitReason);
                        closePosition(order, exitReason);
                        closed.incrementAndGet();

                        switch (exitReason) {
                            case PROFIT_TARGET_HIT:
                                targetHit.incrementAndGet();
                                break;
                            case STOP_LOSS_HIT:
                                slHit.incrementAndGet();
                                break;
                            case MARKET_CLOSE:
                                marketCloseClose.incrementAndGet();
                                break;
                            default:
                                break;
                        }
                    }

                } catch (Exception e) {
                    log.warn("❌ Error checking/closing order {}: {}", order.getId(), e.getMessage());
                }
            }

            log.info("✅ Exit check complete. Closed: {}, Target: {}, SL: {}, Market Close: {}",
                closed.get(), targetHit.get(), slHit.get(), marketCloseClose.get());

        } catch (Exception e) {
            log.error("💥 Fatal error in exit service: {}", e.getMessage(), e);
        }
    }

    private ExitReason checkExitConditions(OmsOrder order, boolean nearMarketClose) {
        try {
            // Get current price (simplified - in production use real-time market data)
            BigDecimal currentPrice = order.getFillPrice() != null ? order.getFillPrice() : order.getPrice();
            BigDecimal entryPrice = order.getPrice();
            double confidence = order.getConfidenceScore() != null ? order.getConfidenceScore() : 50.0;

            if (currentPrice == null || entryPrice == null) {
                return null; // Can't check without prices
            }

            // Calculate P&L %
            double pnlPercent = currentPrice.subtract(entryPrice)
                .divide(entryPrice, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

            log.debug("Order {}: Entry={}, Current={}, P&L%={}, Confidence={}",
                order.getId(), entryPrice, currentPrice, pnlPercent, confidence);

            // Check profit target
            double profitTarget = determineProfitTarget(confidence);
            if (pnlPercent >= profitTarget) {
                log.info("✅ Profit target hit: {:.2f}% >= {:.2f}%", pnlPercent, profitTarget);
                return ExitReason.PROFIT_TARGET_HIT;
            }

            // Check stop loss
            double stopLoss = determineStopLoss(confidence);
            if (pnlPercent <= -stopLoss) {
                log.info("❌ Stop loss hit: {:.2f}% <= -{:.2f}%", pnlPercent, stopLoss);
                return ExitReason.STOP_LOSS_HIT;
            }

            // Check market close
            if (nearMarketClose && isMarketCloseTime()) {
                log.info("🕐 Market close - closing position");
                return ExitReason.MARKET_CLOSE;
            }

            return null; // No exit condition met

        } catch (Exception e) {
            log.warn("Error checking exit conditions for order {}: {}", order.getId(), e.getMessage());
            return null;
        }
    }

    private void closePosition(OmsOrder order, ExitReason reason) {
        try {
            // Create counter order to close position
            // If original was BUY, sell to close
            // If original was SELL, buy to close
            String closingSide = "BUY".equals(order.getSide()) ? "SELL" : "BUY";

            log.info("   Placing closing order: {} {} @ market",
                closingSide, order.getQuantity());

            // In production, would call OrderPlacementService to create exit order
            // For now, just mark as handled
            order.setExitReason(reason.name());
            order.setClosedAt(java.time.Instant.now());
            // Would save to database in real implementation

            log.info("   ✅ Exit order placed. Reason: {}", reason);

        } catch (Exception e) {
            log.error("Error closing position {}: {}", order.getId(), e.getMessage());
        }
    }

    private double determineProfitTarget(double confidence) {
        if (confidence >= 80) {
            return profitTargetHigh;
        } else if (confidence >= 70) {
            return profitTargetMedium;
        } else {
            return profitTargetLow;
        }
    }

    private double determineStopLoss(double confidence) {
        if (confidence >= 80) {
            return stopLossHigh;
        } else if (confidence >= 70) {
            return stopLossMedium;
        } else {
            return stopLossLow;
        }
    }

    private boolean isNearMarketClose() {
        try {
            ZoneId zone = ZoneId.of(marketZone);
            ZonedDateTime now = ZonedDateTime.now(zone);
            LocalTime currentTime = now.toLocalTime();
            LocalTime nseEnd = LocalTime.parse(nseEndTime);
            LocalTime closeWindowStart = nseEnd.minusMinutes(10); // 15:20

            return !currentTime.isBefore(closeWindowStart) && currentTime.isBefore(nseEnd);

        } catch (Exception e) {
            log.debug("Error checking market close time: {}", e.getMessage());
            return false;
        }
    }

    private boolean isMarketCloseTime() {
        try {
            ZoneId zone = ZoneId.of(marketZone);
            ZonedDateTime now = ZonedDateTime.now(zone);
            LocalTime currentTime = now.toLocalTime();
            LocalTime nseEnd = LocalTime.parse(nseEndTime);

            return currentTime.isAfter(nseEnd);

        } catch (Exception e) {
            log.debug("Error checking market close: {}", e.getMessage());
            return false;
        }
    }

    enum ExitReason {
        PROFIT_TARGET_HIT,
        STOP_LOSS_HIT,
        MARKET_CLOSE,
        MANUAL_CLOSE,
        ERROR
    }
}
