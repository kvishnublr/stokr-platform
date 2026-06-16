package com.stokr.admin.simulation;

import com.stokr.common.simulation.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SimulationCleanupService {

    private final SimulationRunRepository runRepository;

    @Transactional
    public Map<String, Object> cleanup(SimulationCleanupRequest request) {
        UUID runId = request.runId();
        String scenario = request.scenario();
        Instant from = request.from();
        Instant to = request.toExclusive();

        int signals = runRepository.softDeleteSignals(runId, scenario, from, to);
        int orders = runRepository.softDeleteOrders(runId, scenario, from, to);
        int executions = runRepository.softDeleteExecutions(runId, scenario, from, to);
        int positions = runRepository.softDeletePositions(runId, scenario, from, to);
        int runs = runRepository.softDeleteRuns(runId, scenario, from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signalsSoftDeleted", signals);
        result.put("ordersSoftDeleted", orders);
        result.put("executionsSoftDeleted", executions);
        result.put("positionsSoftDeleted", positions);
        result.put("runsSoftDeleted", runs);
        return result;
    }

    public record SimulationCleanupRequest(
            UUID runId,
            String scenario,
            Instant from,
            Instant toExclusive
    ) {
    }
}
