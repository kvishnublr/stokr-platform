package com.stokr.admin.simulation;

import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.capital.StrategyCapitalManager;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Verifies simulation artifacts stay out of REAL OMS/capital analytics.
 */
@Service
@RequiredArgsConstructor
public class SimulationIsolationValidator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OmsOrderRepository omsOrderRepository;
    private final StrategyCapitalManager strategyCapitalManager;
    private final StrategySignalRepository signalRepository;

    public IsolationSnapshot captureRealSnapshot(Instant since) {
        return captureSnapshot(since, AnalyticsDataScope.REAL);
    }

    public IsolationSnapshot captureSimulationSnapshot(Instant since) {
        return captureSnapshot(since, AnalyticsDataScope.SIMULATION);
    }

    public IsolationCheckResult check(UUID simulationRunId) {
        Instant since = startOfDayIst();
        IsolationSnapshot real = captureRealSnapshot(since);
        IsolationSnapshot simulation = captureSimulationSnapshot(since);
        long mixedToday = readTodayTotal(
                omsOrderRepository.computeStats(since, AnalyticsDataScope.MIXED.name()));

        boolean scopeConsistent = real.omsTotalToday() + simulation.omsTotalToday() <= mixedToday;
        boolean runVisible = true;
        long runOrders = 0;
        long runSignals = 0;
        if (simulationRunId != null) {
            runOrders = omsOrderRepository.countBySimulationRunIdAndDeletedFalse(simulationRunId);
            runSignals = signalRepository.countBySimulationRunIdAndDeletedFalse(simulationRunId);
            runVisible = runOrders > 0 || runSignals > 0;
        }

        boolean passed = scopeConsistent && (simulationRunId == null || runVisible);
        String message = passed
                ? "REAL and SIMULATION OMS scopes are consistent"
                : "Isolation check failed ??? review scope filters or simulation run visibility";

        return new IsolationCheckResult(
                passed,
                since,
                real,
                simulation,
                mixedToday,
                simulationRunId,
                runOrders,
                runSignals,
                message
        );
    }

    /**
     * Compare REAL metrics before and after a harness run; SIMULATION scope must show new activity.
     */
    public IsolationCheckResult verifyAfterRun(IsolationSnapshot realBaseline, UUID simulationRunId) {
        Instant since = realBaseline.since();
        IsolationSnapshot afterReal = captureRealSnapshot(since);
        IsolationSnapshot afterSimulation = captureSimulationSnapshot(since);

        boolean realUnchanged = realBaseline.matchesOmsAndCapital(afterReal);
        long runOrders = omsOrderRepository.countBySimulationRunIdAndDeletedFalse(simulationRunId);
        long runSignals = signalRepository.countBySimulationRunIdAndDeletedFalse(simulationRunId);
        boolean simulationVisible = runOrders > 0 || runSignals > 0;
        boolean simulationScopeHasRun = runOrders <= afterSimulation.omsTotalToday()
                || runSignals <= afterSimulation.simulationSignalCount();

        boolean passed = realUnchanged && simulationVisible && simulationScopeHasRun;
        String message = passed
                ? "REAL metrics unchanged after simulation; SIMULATION scope shows new data"
                : buildFailureMessage(realUnchanged, simulationVisible, simulationScopeHasRun);

        return new IsolationCheckResult(
                passed,
                since,
                afterReal,
                afterSimulation,
                readTodayTotal(omsOrderRepository.computeStats(since, AnalyticsDataScope.MIXED.name())),
                simulationRunId,
                runOrders,
                runSignals,
                message
        );
    }

    private IsolationSnapshot captureSnapshot(Instant since, AnalyticsDataScope scope) {
        List<Object[]> rows = omsOrderRepository.computeStats(since, scope.name());
        long today = 0;
        long allTime = 0;
        if (!rows.isEmpty()) {
            Object[] r = rows.get(0);
            today = toLong(r[0]);
            allTime = toLong(r[6]);
        }
        BigDecimal utilized = strategyCapitalManager.globalSummary().totalUtilizedCapital();
        long simSignals = scope == AnalyticsDataScope.SIMULATION
                ? signalRepository.countBySimulationTrueAndDeletedFalse()
                : 0;
        return new IsolationSnapshot(since, scope.name(), today, allTime, utilized, simSignals);
    }

    private static Instant startOfDayIst() {
        return ZonedDateTime.now(IST).toLocalDate().atStartOfDay(IST).toInstant();
    }

    private static long readTodayTotal(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return toLong(rows.get(0)[0]);
    }

    private static long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static String buildFailureMessage(boolean realUnchanged, boolean simulationVisible, boolean simulationGrowth) {
        Map<String, Object> issues = new LinkedHashMap<>();
        if (!realUnchanged) {
            issues.put("realMetricsChanged", true);
        }
        if (!simulationVisible) {
            issues.put("simulationRunNotVisible", true);
        }
        if (!simulationGrowth) {
            issues.put("simulationScopeMissingGrowth", true);
        }
        return "Isolation verification failed: " + issues;
    }

    public record IsolationSnapshot(
            Instant since,
            String scope,
            long omsTotalToday,
            long omsTotalAllTime,
            BigDecimal totalUtilizedCapital,
            long simulationSignalCount
    ) {
        boolean matchesOmsAndCapital(IsolationSnapshot other) {
            return other != null
                    && omsTotalToday == other.omsTotalToday()
                    && omsTotalAllTime == other.omsTotalAllTime()
                    && Objects.equals(totalUtilizedCapital, other.totalUtilizedCapital());
        }
    }

    public record IsolationCheckResult(
            boolean passed,
            Instant since,
            IsolationSnapshot realMetrics,
            IsolationSnapshot simulationMetrics,
            long mixedOmsTotalToday,
            UUID simulationRunId,
            long runOrderCount,
            long runSignalCount,
            String message
    ) {
    }
}
