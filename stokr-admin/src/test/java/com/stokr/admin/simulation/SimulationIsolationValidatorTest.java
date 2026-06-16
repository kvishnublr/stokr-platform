package com.stokr.admin.simulation;

import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.capital.GlobalCapitalSummary;
import com.stokr.strategy.capital.StrategyCapitalManager;
import com.stokr.strategy.repository.StrategySignalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationIsolationValidatorTest {

    @Mock
    private OmsOrderRepository omsOrderRepository;
    @Mock
    private StrategyCapitalManager strategyCapitalManager;
    @Mock
    private StrategySignalRepository signalRepository;

    @InjectMocks
    private SimulationIsolationValidator validator;

    @Test
    void verifyAfterRunPassesWhenRealUnchangedAndSimulationVisible() {
        Instant since = Instant.parse("2026-05-30T00:00:00Z");
        UUID runId = UUID.randomUUID();
        SimulationIsolationValidator.IsolationSnapshot baseline =
                new SimulationIsolationValidator.IsolationSnapshot(
                        since, AnalyticsDataScope.REAL.name(), 5, 100, BigDecimal.TEN, 0);

        when(omsOrderRepository.computeStats(eq(since), eq(AnalyticsDataScope.REAL.name())))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 1L, 0L, 0L, 0L, 0L, 100L}));
        when(omsOrderRepository.computeStats(eq(since), eq(AnalyticsDataScope.SIMULATION.name())))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 1L, 0L, 0L, 0L, 0L, 2L}));
        when(omsOrderRepository.computeStats(eq(since), eq(AnalyticsDataScope.MIXED.name())))
                .thenReturn(List.<Object[]>of(new Object[]{7L, 2L, 0L, 0L, 0L, 0L, 102L}));
        when(strategyCapitalManager.globalSummary()).thenReturn(new GlobalCapitalSummary(
                BigDecimal.valueOf(1000), BigDecimal.TEN, BigDecimal.valueOf(990),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 0, 0, List.of()));
        when(signalRepository.countBySimulationTrueAndDeletedFalse()).thenReturn(3L);
        when(omsOrderRepository.countBySimulationRunIdAndDeletedFalse(runId)).thenReturn(1L);
        when(signalRepository.countBySimulationRunIdAndDeletedFalse(runId)).thenReturn(1L);

        var result = validator.verifyAfterRun(baseline, runId);
        assertTrue(result.passed());
    }

    @Test
    void verifyAfterRunFailsWhenRealMetricsDrift() {
        Instant since = Instant.parse("2026-05-30T00:00:00Z");
        UUID runId = UUID.randomUUID();
        SimulationIsolationValidator.IsolationSnapshot baseline =
                new SimulationIsolationValidator.IsolationSnapshot(
                        since, AnalyticsDataScope.REAL.name(), 5, 100, BigDecimal.TEN, 0);

        when(omsOrderRepository.computeStats(eq(since), eq(AnalyticsDataScope.REAL.name())))
                .thenReturn(List.<Object[]>of(new Object[]{6L, 1L, 0L, 0L, 0L, 0L, 101L}));
        when(omsOrderRepository.computeStats(eq(since), eq(AnalyticsDataScope.SIMULATION.name())))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 1L, 0L, 0L, 0L, 0L, 2L}));
        when(strategyCapitalManager.globalSummary()).thenReturn(new GlobalCapitalSummary(
                BigDecimal.valueOf(1000), BigDecimal.TEN, BigDecimal.valueOf(990),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 0, 0, List.of()));
        when(signalRepository.countBySimulationTrueAndDeletedFalse()).thenReturn(3L);
        when(omsOrderRepository.countBySimulationRunIdAndDeletedFalse(runId)).thenReturn(1L);
        when(signalRepository.countBySimulationRunIdAndDeletedFalse(runId)).thenReturn(1L);

        var result = validator.verifyAfterRun(baseline, runId);
        assertFalse(result.passed());
    }
}
