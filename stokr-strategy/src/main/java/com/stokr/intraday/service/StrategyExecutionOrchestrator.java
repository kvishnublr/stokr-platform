package com.stokr.intraday.service;

import com.stokr.common.messaging.MessagePublisher;
import com.stokr.common.messaging.events.AuditEvent;
import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates strategy execution for multiple setup detection strategies.
 * Replaces the old monolithic strategy execution with event-driven architecture.
 *
 * Responsibilities:
 * 1. Scan market data for setups (via detectors)
 * 2. Publish signals to RabbitMQ
 * 3. Log audit trail
 *
 * This service runs independently and publishes events.
 * Execution is handled by the separate Execution Service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyExecutionOrchestrator {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SetupDetectionService setupDetectionService;
    private final SignalPublisherService signalPublisherService;
    private final MessagePublisher messagePublisher;

    @Value("${stokr.strategy.execution-mode:BOTH}") // LIVE, PAPER, or BOTH
    private String executionMode;

    @Value("${stokr.strategy.market-open-hour:9}")
    private Integer marketOpenHour;

    @Value("${stokr.strategy.market-open-minute:15}")
    private Integer marketOpenMinute;

    @Value("${stokr.strategy.market-close-hour:15}")
    private Integer marketCloseHour;

    @Value("${stokr.strategy.market-close-minute:30}")
    private Integer marketCloseMinute;

    /**
     * Main strategy scan loop.
     * Runs every 30 seconds during market hours.
     * Detects setups and publishes signals.
     */
    @Scheduled(fixedDelayString = "${stokr.strategy.scan-interval-ms:30000}")
    public void executeDailyStrategy() {
        try {
            // Check market hours
            LocalTime now = LocalTime.now(IST);
            LocalTime marketOpen = LocalTime.of(marketOpenHour, marketOpenMinute);
            LocalTime marketClose = LocalTime.of(marketCloseHour, marketCloseMinute);

            if (now.isBefore(marketOpen) || now.isAfter(marketClose)) {
                log.debug("Market hours check: Current {} is before {} or after {}",
                        now, marketOpen, marketClose);
                return;
            }

            log.info("📊 STRATEGY SCAN STARTED | Time: {} | Mode: {}", now, executionMode);

            // Scan all stocks in universe
            List<NseStock> universe = getUniverseToScan();
            int signalsPublished = 0;

            for (NseStock stock : universe) {
                try {
                    // Get market data for stock
                    MarketData marketData = fetchMarketData(stock);
                    if (marketData == null) {
                        continue;
                    }

                    // Detect all setups for this stock
                    List<CurrentSetup> detectedSetups = setupDetectionService.detectSetups(
                            stock,
                            marketData.currentPrice,
                            marketData.vwap,
                            marketData.openPrice,
                            marketData.prevClose,
                            marketData.volume,
                            marketData.avgVolume,
                            marketData.atr14,
                            now.getHour(),
                            null, // market regime
                            100 // sample size
                    );

                    // Publish each detected setup as a signal
                    for (CurrentSetup setup : detectedSetups) {
                        try {
                            signalPublisherService.publishSetupAsSignal(
                                    setup,
                                    setup.getSetupType(),
                                    executionMode
                            );
                            signalsPublished++;
                        } catch (Exception e) {
                            log.error("Failed to publish signal for {}: {}", setup.getSymbol(), e.getMessage());
                        }
                    }

                } catch (Exception e) {
                    log.error("Error processing stock {}: {}", stock.getSymbol(), e.getMessage());
                }
            }

            log.info("✅ STRATEGY SCAN COMPLETED | Signals published: {} | Time: {}",
                    signalsPublished, LocalTime.now(IST));

            // Publish audit event
            publishAudit("STRATEGY_SCAN", "Strategy scan cycle completed", signalsPublished);

        } catch (Exception e) {
            log.error("❌ Strategy execution failed", e);
            publishAudit("STRATEGY_SCAN_FAILED", "Strategy scan failed: " + e.getMessage(), -1);
        }
    }

    /**
     * Get the universe of stocks to scan.
     * This can be configurable or dynamic.
     */
    private List<NseStock> getUniverseToScan() {
        // TODO: Load from configuration or database
        // For now, return a sample universe
        return List.of(); // Will be populated by application config
    }

    /**
     * Fetch current market data for a stock.
     */
    private MarketData fetchMarketData(NseStock stock) {
        // TODO: Call market data service via REST
        // GET http://market-data-service:8080/api/v1/market-data/{symbol}
        return null; // Will be implemented
    }

    /**
     * Publish audit event for compliance.
     */
    private void publishAudit(String action, String details, Integer signalCount) {
        try {
            AuditEvent audit = AuditEvent.builder()
                    .auditId("audit-" + UUID.randomUUID())
                    .timestamp(Instant.now())
                    .action(action)
                    .actor("strategy-service")
                    .entityType("SIGNAL")
                    .details(java.util.Map.of(
                            "signalCount", signalCount,
                            "details", details
                    ))
                    .severity(action.contains("FAILED") ? "ERROR" : "INFO")
                    .build();

            messagePublisher.publishAudit(audit);
        } catch (Exception e) {
            log.warn("Failed to publish audit event: {}", e.getMessage());
        }
    }

    /**
     * Inner class for market data.
     */
    public static class MarketData {
        public BigDecimal currentPrice;
        public BigDecimal vwap;
        public BigDecimal openPrice;
        public BigDecimal prevClose;
        public Long volume;
        public Long avgVolume;
        public BigDecimal atr14;

        public MarketData(BigDecimal currentPrice, BigDecimal vwap, BigDecimal openPrice,
                         BigDecimal prevClose, Long volume, Long avgVolume, BigDecimal atr14) {
            this.currentPrice = currentPrice;
            this.vwap = vwap;
            this.openPrice = openPrice;
            this.prevClose = prevClose;
            this.volume = volume;
            this.avgVolume = avgVolume;
            this.atr14 = atr14;
        }
    }
}
