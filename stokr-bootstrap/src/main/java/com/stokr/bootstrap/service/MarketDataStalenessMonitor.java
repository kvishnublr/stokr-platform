package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.MarketDataStalenessLog;
import com.stokr.bootstrap.repository.MarketDataStalenessLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@Profile("v2")
@ConditionalOnProperty(name = "stokr.monitoring.market-data-staleness.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MarketDataStalenessMonitor {

    private final MarketDataStalenessLogRepository sealenessLogRepository;
    private static final int STALENESS_THRESHOLD_SECONDS = 30;

    @Scheduled(fixedRate = 10000)  // Every 10 seconds
    @Transactional
    public void monitorMarketDataFreshness() {
        log.debug("Checking market data freshness...");

        // TODO: Iterate through all symbols with active positions
        // For each symbol, check:
        // 1. Age of last price update
        // 2. Age of last volume update
        // 3. Age of last open interest update

        // Query all feeds (NSE, MCX, etc.)
        // If any feed is stale, log it and alert
    }

    @Transactional
    public void recordStaleness(String symbol, String feedSource, int ageSeconds, boolean isStale) {
        var stalenessLog = MarketDataStalenessLog.builder()
            .id(UUID.randomUUID())
            .symbol(symbol)
            .feedSource(feedSource)
            .lastPriceAgeSeconds(ageSeconds)
            .isStale(isStale)
            .staleThresholdSeconds(STALENESS_THRESHOLD_SECONDS)
            .checkTime(LocalDateTime.now())
            .alertSent(isStale)
            .build();

        sealenessLogRepository.save(stalenessLog);

        if (isStale) {
            log.error("MARKET_DATA_STALE: {} from {} is {} seconds old (threshold: {})",
                symbol, feedSource, ageSeconds, STALENESS_THRESHOLD_SECONDS);
        }
    }

    public boolean isMarketDataStale(String symbol) {
        // TODO: Query the most recent staleness log for this symbol
        // Return true if stale
        return false;
    }

    public void triggerFeedRecovery(String symbol, String feedSource) {
        log.warn("Triggering feed recovery for {} from {}", symbol, feedSource);
        // TODO: Reconnect to feed source
        // TODO: Refresh market data
    }
}
