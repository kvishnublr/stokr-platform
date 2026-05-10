package com.stokr.strategy.runtime;

import com.stokr.strategy.dto.StrategyRestoreBundle;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.service.StrategyCheckpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Reconciles RUNNING instances after JVM restart: loads snapshots and moves runtime to a safe paused state until workers reconcile.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeRecoveryService {

    private static final String STATE_RECOVERING = "RECOVERING";

    private final StrategyInstanceRepository instanceRepository;
    private final StrategyCheckpointService checkpointService;

    @Transactional
    public Optional<StrategyRestoreBundle> recoverInstance(UUID instanceId) {
        var si = instanceRepository.findById(instanceId).filter(x -> !x.isDeleted()).orElse(null);
        if (si == null) {
            return Optional.empty();
        }
        si.setOrchestrationState(STATE_RECOVERING);
        instanceRepository.save(si);

        Optional<StrategyRestoreBundle> bundle = checkpointService.restoreFromCheckpoint(instanceId);

        si.setOrchestrationState("MANAGED");
        si.setRuntimeState("PAUSED");
        instanceRepository.save(si);

        log.warn("strategy.runtime.recovered instanceId={} snapshotPresent={}", instanceId, bundle.isPresent());
        return bundle;
    }
}
