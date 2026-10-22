package com.stokr.arbitrage;

import com.stokr.marketdata.tick.AnomalyDetectionService;
import com.stokr.marketdata.tick.KiteTickWebSocketClient;
import com.stokr.marketdata.tick.TickData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketRegimeAndWebSocketMatrixTest {

    @Test
    @DisplayName("LiveOptionMatrix stores streaming WebSocket ticks and retrieves sub-ms quotes from RAM")
    void testLiveOptionMatrixUpdatesAndFreshness() {
        KiteTickWebSocketClient mockWs = mock(KiteTickWebSocketClient.class);
        LiveOptionMatrix matrix = new LiveOptionMatrix(mockWs);

        TickData tick = TickData.builder()
            .symbol("NIFTY24OCT24000CE")
            .ltp(BigDecimal.valueOf(125.50))
            .volume(45000)
            .build();
        tick.setBestBid(BigDecimal.valueOf(125.00));
        tick.setBestAsk(BigDecimal.valueOf(126.00));
        tick.setBestBidQty(150);
        tick.setBestAskQty(225);
        tick.setOpenInterest(850000);

        matrix.onTick(tick);

        // Sub-ms in-memory lookup
        OptionChainService.OptionQuote q = matrix.getQuote("NIFTY24OCT24000CE", 5000);
        assertNotNull(q, "Quote must be present in RAM");
        assertEquals(125.50, q.lastPrice, 0.001);
        assertEquals(125.00, q.bid, 0.001);
        assertEquals(126.00, q.ask, 0.001);
        assertEquals(150, q.bidQty);
        assertEquals(225, q.askQty);
        assertEquals(850000, q.openInterest);

        // NFO prefixed alias test
        OptionChainService.OptionQuote qPrefixed = matrix.getQuote("NFO:NIFTY24OCT24000CE", 5000);
        assertNotNull(qPrefixed, "NFO: prefix lookup must also succeed");
        assertEquals(125.50, qPrefixed.lastPrice, 0.001);

        // Stale test: query with maxAge of 1ms after thread sleep
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        OptionChainService.OptionQuote staleQ = matrix.getQuote("NIFTY24OCT24000CE", 2);
        assertNull(staleQ, "Quote older than maxAge must be rejected as stale");
    }

    @Test
    @DisplayName("MarketRegimeService blocks Iron Condors during trending markets and permits directional spreads")
    void testMarketRegimeStrategyRouting() {
        ZerodhaSpotPriceFetcher mockSpot = mock(ZerodhaSpotPriceFetcher.class);
        AnomalyDetectionService mockAnomaly = mock(AnomalyDetectionService.class);
        KiteTickWebSocketClient mockWs = mock(KiteTickWebSocketClient.class);

        MarketRegimeService regimeService = new MarketRegimeService(mockSpot, mockAnomaly, mockWs);

        // Setup Bullish Trend scenario: Spot is 25,200, VWAP is 25,000 (+0.80% above VWAP)
        when(mockSpot.getSpotAndFutures(anyString(), anyString())).thenReturn(new double[]{25200.0, 25210.0});
        when(mockAnomaly.getVwap(anyString())).thenReturn(BigDecimal.valueOf(25000.0));

        // Inject 5-minute price history showing higher prices
        regimeService.recordPrice("NIFTY", 25100.0);
        regimeService.recordPrice("NIFTY", 25200.0);

        java.time.LocalTime midDay = java.time.LocalTime.of(11, 0);
        MarketRegime detected = regimeService.detectRegime("NIFTY", midDay);
        assertEquals(MarketRegime.BULLISH_TREND, detected, "Must identify strong bullish trend");

        // Verify Iron Condor is BLOCKED
        assertFalse(regimeService.isStrategyAllowed("IRON_CONDOR", "NIFTY", midDay),
            "Iron Condor MUST be blocked during BULLISH_TREND to prevent short call blowouts");

        // Verify Butterfly is BLOCKED
        assertFalse(regimeService.isStrategyAllowed("BUTTERFLY", "NIFTY", midDay),
            "Butterfly MUST be blocked during strong trend");

        // Verify Directional Vertical Spreads are ALLOWED
        assertTrue(regimeService.isStrategyAllowed("VERTICAL_SPREAD", "NIFTY", midDay),
            "Directional Vertical Spreads MUST be permitted during trend");

        // Setup Sideways Range scenario: Spot is 25,010, VWAP is 25,000 (+0.04% deviation)
        when(mockSpot.getSpotAndFutures(anyString(), anyString())).thenReturn(new double[]{25010.0, 25015.0});
        regimeService.recordPrice("NIFTY", 25005.0);
        regimeService.recordPrice("NIFTY", 25010.0);

        MarketRegime sideways = regimeService.detectRegime("NIFTY", midDay);
        assertEquals(MarketRegime.RANGE_BOUND_CHOP, sideways, "Must identify range-bound chop");

        // In chop, Iron Condor MUST be permitted
        assertTrue(regimeService.isStrategyAllowed("IRON_CONDOR", "NIFTY", midDay),
            "Iron Condor MUST be allowed in range-bound chop");
    }

    @Test
    @DisplayName("SpotDeltaTrigger fires instant scan when spot moves >= 15 pts on NIFTY")
    void testSpotDeltaTrigger() {
        KiteTickWebSocketClient mockWs = mock(KiteTickWebSocketClient.class);
        MultiLegSpreadScheduler mockScheduler = mock(MultiLegSpreadScheduler.class);

        SpotDeltaTrigger trigger = new SpotDeltaTrigger(mockWs, mockScheduler);

        // Tick 1: baseline spot
        TickData t1 = TickData.builder()
            .symbol("NSE:NIFTY 50")
            .ltp(BigDecimal.valueOf(25000.0))
            .build();
        trigger.init(); // registers listener

        // Send tick 1
        trigger.onTick(t1);
        verify(mockScheduler, never()).triggerImmediateScan(anyString());

        // Tick 2: small 5-pt move (below threshold 15)
        TickData t2 = TickData.builder()
            .symbol("NSE:NIFTY 50")
            .ltp(BigDecimal.valueOf(25005.0))
            .build();
        trigger.onTick(t2);
        verify(mockScheduler, never()).triggerImmediateScan(anyString());

        // Tick 3: 20-pt breakout move (25,025 vs 25,000 = 25 pts >= 15 pts)
        TickData t3 = TickData.builder()
            .symbol("NSE:NIFTY 50")
            .ltp(BigDecimal.valueOf(25025.0))
            .build();
        trigger.onTick(t3);
        verify(mockScheduler, times(1)).triggerImmediateScan("NIFTY");
    }
}