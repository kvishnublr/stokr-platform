package com.stokr.intraday.service;

import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.messaging.MessagePublisher;
import com.stokr.common.messaging.events.SignalGeneratedEvent;
import com.stokr.intraday.domain.CurrentSetup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Publishes detected trading setups as signals to RabbitMQ.
 * Strategy Service generates setups, this service converts them to cross-service events.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalPublisherService {
    private final MessagePublisher messagePublisher;

    /**
     * Convert a detected setup into a signal and publish to RabbitMQ.
     * Extraction point: Strategy Service → RabbitMQ → Execution Service
     */
    public SignalGeneratedEvent publishSetupAsSignal(CurrentSetup setup, String strategyName, String executionMode) {
        try {
            // Build signal from detected setup
            SignalGeneratedEvent signal = SignalGeneratedEvent.builder()
                    .signalId("sig-" + UUID.randomUUID())
                    .strategyName(strategyName)
                    .strategyInstance("setup-detector")

                    // Trading details
                    .symbol(setup.getSymbol())
                    .side(setup.getSide()) // "BUY" or "SELL"
                    .quantity(1) // Default position size; could be configurable
                    .lastTradedPrice(setup.getCurrentPrice())

                    // Quality scores
                    .aiScore(calculateAIScore(setup)) // Normalized score 0-100
                    .dayChange(setup.getDayChange())
                    .recentMomentum(setup.getRecentMomentum())
                    .volumeFactor(setup.getVolumeScore())

                    // Execution configuration
                    .executionMode(executionMode) // "LIVE", "PAPER", or "BOTH"
                    .broker("ZERODHA")

                    // Timing
                    .generatedAt(Instant.now())
                    .scanTimestamp(Instant.now())

                    // Risk parameters (informational, Risk Service will validate)
                    .suggestedStopLoss(calculateStopLoss(setup))
                    .suggestedTakeProfit(calculateTakeProfit(setup))

                    // Metadata
                    .correlationId(CorrelationIdHolder.getCorrelationId())

                    .build();

            // Publish to RabbitMQ
            messagePublisher.publishSignalGenerated(signal);

            log.info("✅ SIGNAL PUBLISHED: {} | {} {} | setupType: {} | quality: {}",
                    signal.getSignalId(),
                    signal.getSide(),
                    signal.getSymbol(),
                    setup.getSetupType(),
                    signal.getAiScore());

            return signal;
        } catch (Exception e) {
            log.error("Failed to publish signal for setup: {} | {}", setup.getSymbol(), e.getMessage(), e);
            throw new RuntimeException("Signal publishing failed", e);
        }
    }

    /**
     * Calculate AI score (0-100) from setup quality indicators.
     * This replaces the old aiScore calculation in scanner.
     */
    private Integer calculateAIScore(CurrentSetup setup) {
        // Normalize quality score to 0-100 range
        if (setup.getQualityScore() != null) {
            BigDecimal normalized = setup.getQualityScore()
                    .min(BigDecimal.valueOf(100))
                    .max(BigDecimal.ZERO);
            return normalized.intValue();
        }
        return 50; // Default if not available
    }

    /**
     * Calculate suggested stop loss based on setup.
     */
    private BigDecimal calculateStopLoss(CurrentSetup setup) {
        if (setup.getCurrentPrice() == null) return null;
        // Standard: 1.5% below entry
        return setup.getCurrentPrice()
                .multiply(BigDecimal.valueOf(0.985))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Calculate suggested take profit based on setup.
     */
    private BigDecimal calculateTakeProfit(CurrentSetup setup) {
        if (setup.getCurrentPrice() == null) return null;
        // Standard: 3.0% above entry
        return setup.getCurrentPrice()
                .multiply(BigDecimal.valueOf(1.030))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
