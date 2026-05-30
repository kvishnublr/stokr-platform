package com.stokr.strategy.service;

import com.stokr.common.simulation.SimulationScenario;
import com.stokr.common.simulation.SimulationScenarioContext;
import com.stokr.common.simulation.SimulatedBrokerOutcome;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.common.simulation.OmsAnalyticsFilters;
import com.stokr.common.simulation.AnalyticsDataScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalProvenanceResolverTest {

    private final SignalProvenanceResolver resolver = new SignalProvenanceResolver();

    @AfterEach
    void clearContext() {
        SimulationScenarioContext.clear();
    }

    @Test
    void resolveReturnsSimulationWhenHarnessContextActive() {
        SimulationScenarioContext.set(SimulationScenario.CUSTOM, SimulatedBrokerOutcome.FILLED, UUID.randomUUID());
        StrategySignalEntity signal = new StrategySignalEntity();
        assertEquals(SignalProvenance.SIMULATION, resolver.resolve(signal, "LIVE"));
    }

    @Test
    void resolveReturnsSimulationWhenSignalTagged() {
        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setSimulation(true);
        assertEquals(SignalProvenance.SIMULATION, resolver.resolve(signal, "PAPER"));
    }

    @Test
    void omsRealScopeExcludesSimulationRows() {
        String filter = OmsAnalyticsFilters.orderScopeFilter(AnalyticsDataScope.REAL);
        assertTrue(filter.contains("is_simulation = FALSE"));
    }
}
