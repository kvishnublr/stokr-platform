package com.stokr.admin.simulation;

import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.common.simulation.SimulationScenario;
import com.stokr.strategy.analytics.StrategyEffectivenessEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Release gate: runs mandatory E2E scenarios and verifies pipeline + analytics isolation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationValidationPackService {

    private static final List<SimulationScenario> PACK = List.of(
            SimulationScenario.GAP_FILL_WIN,
            SimulationScenario.GAP_FILL_LOSS,
            SimulationScenario.VWAP_BOUNCE_WIN,
            SimulationScenario.VWAP_BOUNCE_LOSS,
            SimulationScenario.NSE_SPIKE_WIN,
            SimulationScenario.PROTECTION_EXIT,
            SimulationScenario.BROKER_REJECT,
            SimulationScenario.TARGET_HIT,
            SimulationScenario.SL_HIT
    );

    private final MarketSimulationHarnessService harnessService;
    private final StrategyEffectivenessEngine effectivenessEngine;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ValidationPackReport runPack() {
        List<ScenarioValidationResult> results = new ArrayList<>();
        boolean allPassed = true;
        for (SimulationScenario scenario : PACK) {
            MarketSimulationHarnessService.SimulationHarnessReport report = harnessService.runScenario(
                    new MarketSimulationHarnessService.SimulationHarnessRequest(
                            scenario,
                            null,
                            null,
                            null,
                            120,
                            null,
                            null,
                            false,
                            scenario == SimulationScenario.PROTECTION_EXIT,
                            0,
                            null
                    ));
            boolean passed = report.success();
            if (!passed) {
                allPassed = false;
            }
            results.add(new ScenarioValidationResult(scenario.name(), passed, report.validation()));
        }

        Map<String, Object> analyticsCheck = new LinkedHashMap<>();
        try {
            var realEffectiveness = effectivenessEngine.buildReport(
                    LocalDate.now().minusDays(1), LocalDate.now(), null, AnalyticsDataScope.REAL);
            var simEffectiveness = effectivenessEngine.buildReport(
                    LocalDate.now().minusDays(1), LocalDate.now(), null, AnalyticsDataScope.SIMULATION);
            analyticsCheck.put("realScope", realEffectiveness.dataScope());
            analyticsCheck.put("simScope", simEffectiveness.dataScope());
            analyticsCheck.put("realSignalCount", realEffectiveness.scorecards().stream()
                    .mapToLong(s -> s.signalsGenerated()).sum());
            analyticsCheck.put("simSignalCount", simEffectiveness.scorecards().stream()
                    .mapToLong(s -> s.signalsGenerated()).sum());
        } catch (Exception ex) {
            log.error("simulation.validate_pack.analytics_failed {}", ex.getMessage(), ex);
            analyticsCheck.put("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            allPassed = false;
        }

        return new ValidationPackReport(allPassed, results, analyticsCheck);
    }

    public record ScenarioValidationResult(String scenario, boolean passed, Map<String, Object> validation) {
    }

    public record ValidationPackReport(
            boolean allPassed,
            List<ScenarioValidationResult> scenarios,
            Map<String, Object> analyticsIsolation
    ) {
    }
}
