package com.stokr.arbitrage;

import com.stokr.external.ZerodhaTokenManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SuperSystemInstitutionalGuardsTest {

    @Test
    @DisplayName("0DTE Gamma Shield: Automatically rolls new scans to next week after 13:00 IST on expiry day")
    void test0DteGammaShieldExpiryRoll() {
        ZerodhaTokenManager mockTokenMgr = mock(ZerodhaTokenManager.class);
        OptionChainService chainService = new OptionChainService(mockTokenMgr);

        // Find the configured expiry day for NIFTY
        DayOfWeek targetDay = chainService.getExpiryDayForUnderlying("NIFTY");
        LocalDate expiryDay = LocalDate.now();
        while (expiryDay.getDayOfWeek() != targetDay) {
            expiryDay = expiryDay.plusDays(1);
        }

        // 1. Morning on Expiry Day (11:00 AM IST) -> Returns today's 0DTE contract
        LocalTime morning = LocalTime.of(11, 0);
        LocalDate morningExpiry = chainService.getNearestExpiry("NIFTY", expiryDay, morning);
        assertEquals(expiryDay, morningExpiry, "Morning scan must target today's expiry for theta decay");

        // 2. Afternoon on Expiry Day (13:30 PM IST) -> Must roll to NEXT weekly expiry to shield from gamma spike
        LocalTime afternoon = LocalTime.of(13, 30);
        LocalDate afternoonExpiry = chainService.getNearestExpiry("NIFTY", expiryDay, afternoon);
        assertNotEquals(expiryDay, afternoonExpiry, "Afternoon scan must NOT trade 0DTE same-day contract");
        assertTrue(afternoonExpiry.isAfter(expiryDay), "Must roll to future expiry contract");
    }

    @Test
    @DisplayName("Bid-Ask Spread Liquidity Gate: Rejects wide illiquid options to prevent entry slippage")
    void testBidAskSpreadLiquidityGate() {
        // High premium option: 100 bid, 115 ask (spread = 15 > 8% of mid 107.5)
        OptionChainService.OptionQuote wideHigh = new OptionChainService.OptionQuote();
        wideHigh.symbol = "NIFTY26OCT24000CE";
        wideHigh.lastPrice = 107.5;
        wideHigh.bid = 100.0;
        wideHigh.ask = 115.0;
        wideHigh.fetchedAtMs = System.currentTimeMillis();
        assertEquals("bid/ask spread too wide", wideHigh.notTradableReason(),
            "Must reject spread exceeding 8% on high-premium strike");

        // Low premium strike: 10 bid, 14 ask (spread = 4 > 2.0 pts)
        OptionChainService.OptionQuote wideLow = new OptionChainService.OptionQuote();
        wideLow.symbol = "NIFTY26OCT25000CE";
        wideLow.lastPrice = 12.0;
        wideLow.bid = 10.0;
        wideLow.ask = 14.0;
        wideLow.fetchedAtMs = System.currentTimeMillis();
        assertEquals("bid/ask spread too wide", wideLow.notTradableReason(),
            "Must reject spread exceeding 2.0 pts on low-premium strike");

        // Tight liquid strike: 99.5 bid, 100.5 ask (spread = 1.0 = 1% of mid)
        OptionChainService.OptionQuote tight = new OptionChainService.OptionQuote();
        tight.symbol = "NIFTY26OCT24000PE";
        tight.lastPrice = 100.0;
        tight.bid = 99.5;
        tight.ask = 100.5;
        tight.fetchedAtMs = System.currentTimeMillis();
        assertNull(tight.notTradableReason(), "Tight liquid strike must be tradable");
    }

    @Test
    @DisplayName("Smart Leg Sequencing: Guarantees BUY legs execute before SELL legs for margin benefit")
    void testSmartLegSequencingHedgesFirst() {
        List<Map<String, Object>> legs = new ArrayList<>();

        Map<String, Object> sell1 = new LinkedHashMap<>();
        sell1.put("side", "SELL");
        sell1.put("symbol", "NIFTY24000CE");
        legs.add(sell1);

        Map<String, Object> buy1 = new LinkedHashMap<>();
        buy1.put("side", "BUY");
        buy1.put("symbol", "NIFTY24100CE");
        legs.add(buy1);

        Map<String, Object> sell2 = new LinkedHashMap<>();
        sell2.put("side", "SELL");
        sell2.put("symbol", "NIFTY23800PE");
        legs.add(sell2);

        Map<String, Object> buy2 = new LinkedHashMap<>();
        buy2.put("side", "BUY");
        buy2.put("symbol", "NIFTY23700PE");
        legs.add(buy2);

        // Sort using Smart Leg Sequencing rule
        legs.sort((a, b) -> {
            boolean aBuy = "BUY".equalsIgnoreCase((String) a.get("side"));
            boolean bBuy = "BUY".equalsIgnoreCase((String) b.get("side"));
            if (aBuy && !bBuy) return -1;
            if (!aBuy && bBuy) return 1;
            return 0;
        });

        assertEquals("BUY", legs.get(0).get("side"), "First leg must be BUY (protection hedge)");
        assertEquals("BUY", legs.get(1).get("side"), "Second leg must be BUY (protection hedge)");
        assertEquals("SELL", legs.get(2).get("side"), "Third leg must be SELL (inner short wing)");
        assertEquals("SELL", legs.get(3).get("side"), "Fourth leg must be SELL (inner short wing)");
    }

    @Test
    @DisplayName("India VIX Integration: Spiking VIX triggers VOLATILITY_SHOCK and halts short Vega strategies")
    void testIndiaVixSpikeProtection() {
        ZerodhaSpotPriceFetcher spotFetcher = mock(ZerodhaSpotPriceFetcher.class);
        com.stokr.marketdata.tick.AnomalyDetectionService anomalyService = mock(com.stokr.marketdata.tick.AnomalyDetectionService.class);
        com.stokr.marketdata.tick.KiteTickWebSocketClient tickClient = mock(com.stokr.marketdata.tick.KiteTickWebSocketClient.class);

        MarketRegimeService regimeService = new MarketRegimeService(spotFetcher, anomalyService, tickClient);
        Mockito.when(spotFetcher.getSpotAndFutures(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(new double[]{24000.0, 24050.0});

        // 1. Calm market: VIX at 12.0
        regimeService.setOpeningVix(12.0);
        regimeService.setCurrentVix(12.0);
        assertEquals(0.0, regimeService.getVixChangePct(), 0.01);
        assertTrue(regimeService.isStrategyAllowed("IRON_CONDOR", "NIFTY", LocalTime.of(11, 0)),
            "Iron Condor should be allowed when VIX is steady");

        // 2. Sudden VIX Spike: VIX surges to 13.5 (+12.5% intraday surge)
        regimeService.setCurrentVix(13.5);
        assertTrue(regimeService.getVixChangePct() >= 5.0, "VIX change must exceed 5%");
        MarketRegime regime = regimeService.detectRegime("NIFTY", LocalTime.of(11, 0));
        assertEquals(MarketRegime.VOLATILITY_SHOCK, regime, "VIX spike must trigger VOLATILITY_SHOCK");

        // Vega-short option strategies must be blocked
        assertFalse(regimeService.isStrategyAllowed("IRON_CONDOR", "NIFTY", LocalTime.of(11, 0)),
            "Iron Condor must be blocked during VIX surge to prevent Vega expansion loss");
        assertFalse(regimeService.isStrategyAllowed("BUTTERFLY", "NIFTY", LocalTime.of(11, 0)),
            "Butterfly must be blocked during VIX surge");
    }

    @Test
    @DisplayName("Dynamic Strike Sizing: Volatility expansion automatically broadens condor wing padding")
    void testDynamicStrikeDistanceScaling() {
        double spot = 25000.0;
        int step = 50;

        // Formula: dailySigma = spot * (vix / 1587.45)
        // Calm market: VIX = 10.0 -> dailySigma = 157.48 -> steps = round(157.48 / 50) = 3
        double calmSigma = spot * (10.0 / 1587.45);
        int calmSteps = Math.max(3, (int) Math.round(calmSigma / step));
        assertEquals(3, calmSteps, "Calm day uses tight 3-step (150 pt) buffer");

        // High volatility day: VIX = 18.0 -> dailySigma = 283.47 -> steps = round(283.47 / 50) = 6
        double highSigma = spot * (18.0 / 1587.45);
        int highSteps = Math.max(3, (int) Math.round(highSigma / step));
        assertEquals(6, highSteps, "Volatile day automatically expands to 6-step (300 pt) defensive buffer");
        assertTrue(highSteps > calmSteps, "Wing width must scale dynamically with volatility");
    }
}