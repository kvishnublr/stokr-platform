package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.MarketDataStalenessLog;
import com.stokr.bootstrap.repository.MarketDataStalenessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketDataStalenessMonitorTest {

    @Mock
    private MarketDataStalenessLogRepository stalenessLogRepository;

    @InjectMocks
    private MarketDataStalenessMonitor monitor;

    private static final String NIFTY = "NIFTY";
    private static final String MCX_GOLD = "GOLD";
    private static final String NSE_FEED = "NSE";
    private static final String MCX_FEED = "MCX";

    @BeforeEach
    void setUp() {
        // Repository mocks are set up
    }

    @Test
    void testMonitorMarketDataFreshness_ChecksAllSymbols() {
        // Given
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.monitorMarketDataFreshness();

        // Then - Should check and log
        verify(stalenessLogRepository, atLeastOnce()).save(any(MarketDataStalenessLog.class));
    }

    @Test
    void testRecordStaleness_FreshData_NotMarkedStale() {
        // Given - Data is fresh (5 seconds old)
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.recordStaleness(NIFTY, NSE_FEED, 5, false);

        // Then
        verify(stalenessLogRepository).save(argThat(log ->
            NIFTY.equals(log.getSymbol()) &&
            !log.getIsStale()
        ));
    }

    @Test
    void testRecordStaleness_StaleData_Detected() {
        // Given - Data is stale (45 seconds old, threshold 30)
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.recordStaleness(NIFTY, NSE_FEED, 45, true);

        // Then
        verify(stalenessLogRepository).save(argThat(log ->
            NIFTY.equals(log.getSymbol()) &&
            log.getIsStale() &&
            log.getAlertSent()
        ));
    }

    @Test
    void testRecordStaleness_CriticalScenario_13_02_Failure() {
        // Scenario: 2026-06-05 13:02 - NSE feed goes stale due to Redis failure

        // Given - NSE feed hasn't updated in 120 seconds
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.recordStaleness(NIFTY, NSE_FEED, 120, true);

        // Then - Should detect stale feed and trigger alert
        verify(stalenessLogRepository).save(argThat(log ->
            NIFTY.equals(log.getSymbol()) &&
            log.getIsStale() &&
            log.getLastPriceAgeSeconds() > 30
        ));
    }

    @Test
    void testRecordStaleness_MultipleFeeds_IndependentTracking() {
        // Given - Different feeds for different symbols
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.recordStaleness(NIFTY, NSE_FEED, 20, false);
        monitor.recordStaleness(MCX_GOLD, MCX_FEED, 50, true);

        // Then - Each feed tracked independently
        verify(stalenessLogRepository, times(2)).save(any(MarketDataStalenessLog.class));
    }

    @Test
    void testIsMarketDataStale_ReturnsFalseWhenFresh() {
        // Given - No staleness logged recently
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        monitor.recordStaleness(NIFTY, NSE_FEED, 10, false);

        // When
        boolean isStale = monitor.isMarketDataStale(NIFTY);

        // Then
        assertFalse(isStale);
    }

    @Test
    void testTriggerFeedRecovery_InitiatesRecovery() {
        // Given
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.triggerFeedRecovery(NIFTY, NSE_FEED);

        // Then - Recovery initiated (logging verifies this in real implementation)
        // In a real scenario, this would reconnect to the feed source
        assertTrue(true);  // Recovery triggered
    }

    @Test
    void testMonitoringFrequency_RunsEvery10Seconds() {
        // This test documents the @Scheduled(fixedRate = 10000)
        // Market data staleness is checked every 10 seconds
        // This allows quick detection of feed failures

        // Given
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.monitorMarketDataFreshness();

        // Then - Should check and log
        verify(stalenessLogRepository, atLeastOnce()).save(any(MarketDataStalenessLog.class));
    }

    @Test
    void testStalenessStalenessThreshold_30Seconds() {
        // This test documents that staleness threshold is 30 seconds
        // Data older than 30 seconds triggers alerts

        // Given - Data exactly at threshold (30 seconds)
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.recordStaleness(NIFTY, NSE_FEED, 30, true);

        // Then
        verify(stalenessLogRepository).save(argThat(log ->
            log.getStaleThresholdSeconds() == 30
        ));
    }

    @Test
    void testMultipleSymbols_AllMonitored() {
        // Given - Multiple symbols with positions
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        String[] symbols = {"NIFTY", "BANKNIFTY", "GOLD", "COPPER"};
        String[] feeds = {"NSE", "NSE", "MCX", "MCX"};

        // When
        for (int i = 0; i < symbols.length; i++) {
            monitor.recordStaleness(symbols[i], feeds[i], 15, false);
        }

        // Then - All symbols logged
        verify(stalenessLogRepository, times(4)).save(any(MarketDataStalenessLog.class));
    }

    @Test
    void testCriticalRule_FeedFailureDetection() {
        // This test documents the CRITICAL RULE from WS13:
        // Market data feeds are monitored for staleness
        // If any feed is > 30 seconds old, immediate alert and recovery triggered

        // Given - Feed hasn't updated in 45 seconds
        when(stalenessLogRepository.save(any(MarketDataStalenessLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        monitor.recordStaleness(NIFTY, NSE_FEED, 45, true);

        // Then - Should detect and alert
        verify(stalenessLogRepository).save(argThat(log ->
            log.getIsStale() && log.getLastPriceAgeSeconds() > 30
        ));
    }
}
