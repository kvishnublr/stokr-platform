package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.domain.StrategyStateSnapshot;
import com.stokr.strategy.dto.StrategyRestoreBundle;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategyStateSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategyCheckpointService {

    private final StrategyInstanceRepository instanceRepository;
    private final StrategyStateSnapshotRepository snapshotRepository;

    @Transactional
    public StrategyStateSnapshot saveSnapshot(UUID instanceId, String stateJson, String indicatorJson, String replayCheckpoint) {
        StrategyInstance si = instanceRepository.findById(instanceId).filter(x -> !x.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("instance not found"));
        long nextSeq = snapshotRepository.findTopByInstance_IdAndDeletedFalseOrderBySequenceNumDesc(instanceId)
                .map(s -> s.getSequenceNum() + 1)
                .orElse(1L);
        StrategyStateSnapshot snap = new StrategyStateSnapshot();
        snap.setInstance(si);
        snap.setSequenceNum(nextSeq);
        snap.setStateJson(stateJson);
        snap.setIndicatorJson(indicatorJson);
        snap.setReplayCheckpoint(replayCheckpoint);
        return snapshotRepository.save(snap);
    }

    @Transactional(readOnly = true)
    public StrategyStateSnapshot latest(UUID instanceId) {
        return snapshotRepository.findTopByInstance_IdAndDeletedFalseOrderBySequenceNumDesc(instanceId).orElse(null);
    }

    /**
     * Loads latest persisted snapshot for restart reconciliation (executor applies JSON before generating new signals).
     */
    @Transactional(readOnly = true)
    public Optional<StrategyRestoreBundle> restoreFromCheckpoint(UUID instanceId) {
        return snapshotRepository.findTopByInstance_IdAndDeletedFalseOrderBySequenceNumDesc(instanceId)
                .map(s -> new StrategyRestoreBundle(
                        s.getStateJson(),
                        s.getIndicatorJson(),
                        s.getReplayCheckpoint(),
                        s.getSequenceNum(),
                        s.getId()
                ));
    }
}
