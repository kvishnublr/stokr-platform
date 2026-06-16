package com.stokr.admin.simulation;

import com.stokr.common.simulation.domain.SimulationRunEntity;
import com.stokr.common.simulation.repository.SimulationRunRepository;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SimulationDashboardService {

    private final SimulationRunRepository runRepository;
    private final StrategySignalRepository signalRepository;
    private final OmsOrderRepository orderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public SimulationDashboardSnapshot dashboard(UUID runId) {
        List<SimulationRunEntity> runs = runId != null
                ? runRepository.findById(runId).map(List::of).orElse(List.of())
                : runRepository.findTop50ByDeletedFalseOrderByStartedAtDesc();

        List<Map<String, Object>> runSummaries = new ArrayList<>();
        for (SimulationRunEntity run : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", run.getId().toString());
            m.put("scenario", run.getScenario());
            m.put("status", run.getStatus());
            m.put("success", run.getSuccess());
            m.put("startedAt", run.getStartedAt());
            m.put("completedAt", run.getCompletedAt());
            m.put("signalCount", countSignals(run.getId()));
            m.put("orderCount", countOrders(run.getId()));
            runSummaries.add(m);
        }

        List<StrategySignalEntity> signals = runId != null
                ? signalRepository.findBySimulationRunIdAndDeletedFalseOrderByCreatedAtDesc(runId)
                : List.of();

        List<Map<String, Object>> signalRows = signals.stream().map(this::signalRow).toList();

        return new SimulationDashboardSnapshot(runSummaries, signalRows, aggregateOutcomes(runId));
    }

    private Map<String, Object> signalRow(StrategySignalEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signalId", s.getId().toString());
        m.put("strategy", s.getStrategyName());
        m.put("symbol", s.getSymbol());
        m.put("confidence", s.getConfidenceScore());
        m.put("confidenceVersion", s.getConfidenceVersion());
        m.put("outcomeStatus", s.getOutcomeStatus());
        m.put("targetPrice", s.getTargetPrice());
        m.put("stopPrice", s.getStopPrice());
        m.put("realizedPnl", s.getRealizedPnl());
        m.put("scenario", s.getSimulationScenario());
        List<OmsOrder> orders = orderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(s.getId());
        m.put("orderState", orders.isEmpty() ? null : orders.get(0).getState().name());
        return m;
    }

    private Map<String, Object> aggregateOutcomes(UUID runId) {
        if (runId == null) {
            return Map.of();
        }
        String sql = """
                SELECT
                    COUNT(*)::bigint,
                    COUNT(*) FILTER (WHERE outcome_status = 'TARGET_HIT')::bigint,
                    COUNT(*) FILTER (WHERE outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint,
                    COUNT(*) FILTER (WHERE outcome_status LIKE '%PROTECT%')::bigint,
                    COUNT(*) FILTER (WHERE confidence_score IS NOT NULL)::bigint
                FROM strategy_signals
                WHERE deleted = FALSE AND is_simulation = TRUE AND simulation_run_id = :runId
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("runId", runId);
        Object[] row = (Object[]) q.getSingleResult();
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("signals", ((Number) row[0]).longValue());
        agg.put("targetHits", ((Number) row[1]).longValue());
        agg.put("stopLosses", ((Number) row[2]).longValue());
        agg.put("protectionExits", ((Number) row[3]).longValue());
        agg.put("confidencePopulated", ((Number) row[4]).longValue());
        return agg;
    }

    private long countSignals(UUID runId) {
        return signalRepository.countBySimulationRunIdAndDeletedFalse(runId);
    }

    private long countOrders(UUID runId) {
        return orderRepository.countBySimulationRunIdAndDeletedFalse(runId);
    }

    public record SimulationDashboardSnapshot(
            List<Map<String, Object>> runs,
            List<Map<String, Object>> signals,
            Map<String, Object> aggregates
    ) {
    }
}
