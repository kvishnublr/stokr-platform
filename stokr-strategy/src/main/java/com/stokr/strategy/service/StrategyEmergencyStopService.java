package com.stokr.strategy.service;

import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stops all running strategy instances (paper/sim/live orchestration) ??? operations safety valve.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyEmergencyStopService {

    private final StrategyInstanceRepository strategyInstanceRepository;

    @Transactional
    public int stopAllRunning() {
        var running = strategyInstanceRepository.findAllByRuntimeStateAndDeletedFalse("RUNNING");
        int n = 0;
        for (var si : running) {
            si.setRuntimeState("STOPPED");
            si.setOrchestrationState("ERROR");
            strategyInstanceRepository.save(si);
            n++;
        }
        log.warn("strategy.emergency_stop count={}", n);
        return n;
    }
}
