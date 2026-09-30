package com.stokr.smartstrategy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.stokr.smartstrategy.MarketRegimeDetector.Regime.*;
import static org.junit.jupiter.api.Assertions.*;

class RegimeAndSizingTest {

    @Test
    void regimeFollowsTheDaysMoveNotFuturesCarry() {
        assertEquals(SIDEWAYS, MarketRegimeDetector.classify(50, 0.8, 1.0, 0.2));
        assertEquals(TRENDING_UP, MarketRegimeDetector.classify(50, 0.9, 1.0, 0.8));
        assertEquals(TRENDING_DOWN, MarketRegimeDetector.classify(50, 0.9, 1.0, -0.8));
        assertEquals(HIGH_VOLATILE, MarketRegimeDetector.classify(50, 2.5, 1.0, 0.1));
        assertEquals(HIGH_VOLATILE, MarketRegimeDetector.classify(80, 1.6, 1.0, 0.1));
        // No OHLC yet (range/change 0) ⇒ neutral, never a fabricated trend
        assertEquals(SIDEWAYS, MarketRegimeDetector.classify(50, 0, 1.0, 0));
    }

    @Test
    void lotsFollowScore() {
        assertEquals(1, SmartAutoEntryService.lotsForScore(Map.of("compositeScore", 79.9)));
        assertEquals(2, SmartAutoEntryService.lotsForScore(Map.of("compositeScore", 80)));
        assertEquals(1, SmartAutoEntryService.lotsForScore(Map.of()));
    }

    @Test
    void targetUsesStructuralMaxProfitFirst() {
        // BWB: max profit (narrow + credit) is larger than the credit alone
        assertEquals(9000, SmartAutoEntryService.maxProfitPerLot(Map.of("maxProfit", 9000, "creditRs", 1500)));
        assertEquals(1500, SmartAutoEntryService.maxProfitPerLot(Map.of("creditRs", 1500)));
        assertEquals(700, SmartAutoEntryService.maxProfitPerLot(Map.of("netEdgeRs", 700)));
        assertEquals(0, SmartAutoEntryService.maxProfitPerLot(Map.of()));
    }
}
