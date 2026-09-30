package com.stokr.smartstrategy;

import com.stokr.arbitrage.LivePositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioRiskManagerTest {

    private PortfolioRiskManager risk;

    @BeforeEach
    void setUp() {
        LivePositionRepository repo = mock(LivePositionRepository.class);
        when(repo.findAllOpen()).thenReturn(List.of());
        when(repo.findAll()).thenReturn(List.of());
        risk = new PortfolioRiskManager(repo);
    }

    @Test
    void maxLossPerLotReadsEveryScannerKey() {
        assertEquals(5000, PortfolioRiskManager.maxLossPerLot(Map.of("maxLoss", 5000)));
        assertEquals(3000, PortfolioRiskManager.maxLossPerLot(Map.of("maxLossDown", 3000)));
        assertEquals(2000, PortfolioRiskManager.maxLossPerLot(Map.of("maxLossPut", 2000)));
        assertEquals(0, PortfolioRiskManager.maxLossPerLot(Map.of("maxLoss", 0)));
    }

    @Test
    void unknownOrZeroMaxLossIsBlocked() {
        var check = risk.canEnterTrade(Map.of("strategyType", "SKEW_HARVEST", "underlying", "NIFTY"), 1);
        assertFalse(check.allowed());
        assertTrue(check.reason().contains("Max loss unknown"));

        var zero = risk.canEnterTrade(Map.of("strategyType", "BOX_SPREAD_ARB", "underlying", "NIFTY", "maxLoss", 0), 1);
        assertFalse(zero.allowed());
    }

    @Test
    void riskBudgetScalesWithLots() {
        // Budget is 3 × daily loss cap (₹45,000). ₹25,000/lot fits at 1 lot, not at 2.
        Map<String, Object> opp = Map.of("strategyType", "IRON_CONDOR", "underlying", "NIFTY", "maxLoss", 25000);
        assertTrue(risk.canEnterTrade(opp, 1).allowed());
        assertFalse(risk.canEnterTrade(opp, 2).allowed());
    }
}
