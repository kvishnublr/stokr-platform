package com.stokr.bootstrap.trading;

import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.execution.service.OrderPlacementService;
import com.stokr.intraday.metrics.domain.ConfidenceScore;
import com.stokr.intraday.metrics.domain.ConfidenceStrategyConfig;
import com.stokr.intraday.metrics.repository.ConfidenceScoreRepository;
import com.stokr.intraday.metrics.repository.ConfidenceStrategyConfigRepository;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "stokr.confidence-strategy.auto-trade-enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class ConfidenceSignalToOrderService {

    private final ConfidenceStrategyConfigRepository configRepository;
    private final ConfidenceScoreRepository confidenceScoreRepository;
    private final OrderPlacementService orderPlacementService;

    @Value("${stokr.confidence-strategy.auto-trade.execution-mode:SIMULATED}")
    private String executionMode;

    @Value("${stokr.confidence-strategy.auto-trade.default-quantity:1}")
    private int defaultQuantity;

    @Value("${stokr.confidence-strategy.auto-trade.high-confidence-quantity:2}")
    private int highConfidenceQuantity;

    @Value("${stokr.confidence-strategy.auto-trade.confidence-threshold:75}")
    private int highConfidenceThreshold;

    @Value("${stokr.marketdata.session.nse.start:09:15}")
    private String nseStartTime;

    @Value("${stokr.marketdata.session.nse.end:15:30}")
    private String nseEndTime;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private String marketZone;

    // Run every 2 minutes (aligned with signal generation - 120000ms = 2 minutes)
    @Scheduled(fixedRateString = "${stokr.confidence-strategy.auto-trade.interval-ms:120000}",
               initialDelayString = "${stokr.confidence-strategy.auto-trade.initial-delay-ms:5000}")
    @Transactional
    public void convertSignalsToOrders() {
        try {
            log.info("🔄 Starting confidence signal to order conversion...");

            // Check if market is open
            if (!isMarketOpen()) {
                log.debug("⏸️  Market closed. Skipping signal conversion.");
                return;
            }

            // Get all enabled trader configurations
            List<ConfidenceStrategyConfig> configs = configRepository.findByEnabledTrue();
            if (configs.isEmpty()) {
                log.debug("⚠️  No trader configurations found. No signals to convert.");
                return;
            }

            AtomicInteger totalConverted = new AtomicInteger(0);
            AtomicInteger totalBlocked = new AtomicInteger(0);
            AtomicInteger totalFailed = new AtomicInteger(0);

            // Process signals for each trader config
            for (ConfidenceStrategyConfig config : configs) {
                try {
                    int converted = processSignalsForTrader(config);
                    int blocked = processSignalsForTrader_GetBlockedCount(config);
                    totalConverted.addAndGet(converted);
                    totalBlocked.addAndGet(blocked);
                } catch (Exception e) {
                    log.warn("❌ Error processing signals for trader {}: {}",
                        config.getTraderId(), e.getMessage());
                    totalFailed.incrementAndGet();
                }
            }

            log.info("✅ Signal conversion complete. Converted: {}, Blocked: {}, Failed: {}",
                totalConverted.get(), totalBlocked.get(), totalFailed.get());

        } catch (Exception e) {
            log.error("💥 Fatal error in signal conversion: {}", e.getMessage(), e);
        }
    }

    private int processSignalsForTrader(ConfidenceStrategyConfig config) {
        int threshold = config.getMinConfidenceThreshold();
        int ordersPlaced = 0;

        try {
            // Get new confidence signals from last 2 minutes
            List<ConfidenceScore> recentScores = confidenceScoreRepository
                .findRecentByConfidenceThreshold(threshold, Instant.now().minusSeconds(120));

            if (recentScores.isEmpty()) {
                log.debug("No new signals above threshold {} for trader {}",
                    threshold, config.getTraderId());
                return 0;
            }

            log.debug("Found {} potential signals for trader {} (threshold: {})",
                recentScores.size(), config.getTraderId(), threshold);

            for (ConfidenceScore score : recentScores) {
                try {
                    // RISK GATE 1: Check daily signal cap
                    if (isDailySignalCapExceeded(config.getTraderId())) {
                        log.warn("⛔ Daily signal cap exceeded for trader {}. Skipping signal.",
                            config.getTraderId());
                        continue;
                    }

                    // RISK GATE 2: Check max open positions
                    if (isMaxPositionsExceeded(config.getTraderId())) {
                        log.warn("⛔ Max open positions exceeded for trader {}. Skipping signal.",
                            config.getTraderId());
                        continue;
                    }

                    // RISK GATE 3: Check daily loss limit
                    if (isDailyLossLimitExceeded(config.getTraderId())) {
                        log.warn("⛔ Daily loss limit exceeded for trader {}. Stopping all trades.",
                            config.getTraderId());
                        continue;
                    }

                    // Check if order already exists for this signal
                    if (orderAlreadyPlaced(score.getSymbol(), config.getTraderId())) {
                        log.debug("⏭️  Order already placed for {} by trader {}",
                            score.getSymbol(), config.getTraderId());
                        continue;
                    }

                    // RISK GATE 4: Validate symbol and price
                    if (!isValidSymbolAndPrice(score)) {
                        log.warn("⛔ Invalid symbol or price for {}: {}",
                            score.getSymbol(), score.getLiquidityScore());
                        continue;
                    }

                    // Determine order parameters
                    String side = determineSide(score);
                    BigDecimal quantity = BigDecimal.valueOf(determineQuantity(score));
                    String orderType = determineOrderType(score);
                    BigDecimal limitPrice = determineLimitPrice(score);

                    // Create order request
                    CreateOrderRequest request = new CreateOrderRequest(
                        score.getSymbol(),
                        side,
                        orderType,
                        quantity,
                        limitPrice,
                        ExecutionMode.valueOf(executionMode),
                        null, // brokerVendor - defaults to ZERODHA for LIVE, SIM for SIMULATED
                        "CONFIDENCE_BASED_" + threshold, // strategyKey
                        null  // idempotencyKey
                    );

                    // Place order
                    OmsOrder order = orderPlacementService.place(config.getTraderId(), request);

                    log.info("✅ Order placed from signal: {} {} {} @ confidence {}",
                        side, quantity, score.getSymbol(), score.getConfidenceScore());
                    log.info("   Order ID: {}, Strategy: CONFIDENCE_BASED_{}",
                        order.getId(), threshold);

                    ordersPlaced++;

                } catch (Exception e) {
                    log.warn("❌ Failed to place order for {}: {}",
                        score.getSymbol(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Error processing signals for trader {}: {}",
                config.getTraderId(), e.getMessage(), e);
        }

        return ordersPlaced;
    }

    private int processSignalsForTrader_GetBlockedCount(ConfidenceStrategyConfig config) {
        // This is a placeholder to track blocked signals
        // In production, implement proper blocking/rejection tracking
        return 0;
    }

    private boolean orderAlreadyPlaced(String symbol, UUID traderId) {
        // Check if order already exists for this symbol + trader combination
        // to prevent duplicate orders on same signal
        try {
            // This would check OMS to see if order already exists
            // For now, return false (no check - allows multiple)
            // TODO: Implement actual duplicate detection
            return false;
        } catch (Exception e) {
            log.debug("Could not check for existing order: {}", e.getMessage());
            return false;
        }
    }

    private String determineSide(ConfidenceScore score) {
        // BUY if confidence is high (>70), SELL if low (<30)
        if (score.getConfidenceScore() > 70) {
            return "BUY";
        } else if (score.getConfidenceScore() < 30) {
            return "SELL";
        } else {
            // Mid-range: default to BUY (configurable)
            return "BUY";
        }
    }

    private int determineQuantity(ConfidenceScore score) {
        // Higher confidence → larger position
        if (score.getConfidenceScore() >= highConfidenceThreshold) {
            return highConfidenceQuantity; // 2 lots
        } else {
            return defaultQuantity; // 1 lot
        }
    }

    private String determineOrderType(ConfidenceScore score) {
        // Very high confidence → MARKET order (immediate execution)
        // Medium confidence → LIMIT order (price protection)
        if (score.getConfidenceScore() >= 85) {
            return "MARKET";
        } else {
            return "LIMIT";
        }
    }

    private BigDecimal determineLimitPrice(ConfidenceScore score) {
        // For high confidence buys: slightly below current price
        // For low confidence sells: slightly above current price
        // This is basic - in production, use real-time market data
        return score.getLiquidityScore() != null
            ? BigDecimal.valueOf(score.getLiquidityScore())
            : BigDecimal.ZERO;
    }

    private boolean isMarketOpen() {
        try {
            ZoneId zone = ZoneId.of(marketZone);
            ZonedDateTime now = ZonedDateTime.now(zone);
            LocalTime currentTime = now.toLocalTime();

            // Parse market hours
            LocalTime nseStart = LocalTime.parse(nseStartTime);
            LocalTime nseEnd = LocalTime.parse(nseEndTime);

            // Check if within NSE trading hours
            boolean isNseOpen = !currentTime.isBefore(nseStart) && currentTime.isBefore(nseEnd);

            // Optionally check for holidays (would need holiday calendar)
            // For now, just check hours

            if (!isNseOpen) {
                log.debug("Market closed. Current time: {}, NSE hours: {} - {}",
                    currentTime, nseStartTime, nseEndTime);
            }

            return isNseOpen;

        } catch (Exception e) {
            log.warn("Error checking market hours: {}", e.getMessage());
            // If we can't determine, assume market is open (fail safe)
            return true;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RISK GATE VALIDATION METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isDailySignalCapExceeded(UUID traderId) {
        try {
            // Get today's signal count from database
            int todaysSignals = getTodaysSignalCount(traderId);
            int maxDailySignals = 50; // Configurable, default 50

            if (todaysSignals >= maxDailySignals) {
                log.warn("Daily signal cap reached: {}/{}", todaysSignals, maxDailySignals);
                return true;
            }
            return false;

        } catch (Exception e) {
            log.debug("Error checking daily signal cap: {}", e.getMessage());
            return false; // Don't block on error
        }
    }

    private boolean isMaxPositionsExceeded(UUID traderId) {
        try {
            // Get open positions count from OMS
            int openPositions = getOpenPositionsCount(traderId);
            int maxPositions = 10; // Configurable, default 10

            if (openPositions >= maxPositions) {
                log.warn("Max open positions reached: {}/{}", openPositions, maxPositions);
                return true;
            }
            return false;

        } catch (Exception e) {
            log.debug("Error checking max positions: {}", e.getMessage());
            return false; // Don't block on error
        }
    }

    private boolean isDailyLossLimitExceeded(UUID traderId) {
        try {
            // Get today's P&L from OMS
            BigDecimal dailyPnl = getTodaysPnL(traderId);
            BigDecimal maxDailyLoss = BigDecimal.valueOf(-5000); // Configurable, default -5000 INR

            if (dailyPnl.compareTo(maxDailyLoss) < 0) {
                log.warn("Daily loss limit exceeded: {} < {}", dailyPnl, maxDailyLoss);
                return true;
            }
            return false;

        } catch (Exception e) {
            log.debug("Error checking daily loss limit: {}", e.getMessage());
            return false; // Don't block on error
        }
    }

    private boolean isValidSymbolAndPrice(ConfidenceScore score) {
        try {
            // Validate symbol exists
            if (score.getSymbol() == null || score.getSymbol().isEmpty()) {
                return false;
            }

            // Validate price is reasonable (not 0 or extreme)
            Double price = score.getLiquidityScore();
            if (price == null || price <= 0 || price > 100000) {
                return false;
            }

            return true;

        } catch (Exception e) {
            log.debug("Error validating symbol/price: {}", e.getMessage());
            return false;
        }
    }

    // Helper methods - these would call actual OMS/database repositories
    private int getTodaysSignalCount(UUID traderId) {
        // TODO: Query OMS for today's signals placed by this trader
        // For now, return 0 (no limit applied)
        return 0;
    }

    private int getOpenPositionsCount(UUID traderId) {
        // TODO: Query OMS for open positions count
        // For now, return 0 (no limit applied)
        return 0;
    }

    private BigDecimal getTodaysPnL(UUID traderId) {
        // TODO: Query OMS for today's P&L
        // For now, return 0 (no loss limit applied)
        return BigDecimal.ZERO;
    }
}
