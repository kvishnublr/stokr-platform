package com.stokr.admin.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.common.simulation.domain.SimulationRunEntity;
import com.stokr.common.simulation.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SimulationRunService {

    private final SimulationRunRepository runRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SimulationRunEntity startRun(String scenario, UUID startedBy) {
        SimulationRunEntity run = new SimulationRunEntity();
        run.setId(UUID.randomUUID());
        run.setScenario(scenario);
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        run.setStartedBy(startedBy);
        run.setDeleted(false);
        return runRepository.save(run);
    }

    @Transactional
    public void completeRun(UUID runId, boolean success, Object report) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus("COMPLETED");
            run.setSuccess(success);
            run.setCompletedAt(Instant.now());
            if (report != null) {
                try {
                    run.setReportJson(objectMapper.writeValueAsString(report));
                } catch (Exception ignored) {
                    run.setReportJson("{}");
                }
            }
            runRepository.save(run);
        });
    }
}
