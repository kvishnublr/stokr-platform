package com.stokr.oms.journal;

import com.stokr.oms.journal.domain.EventStoreEntry;
import com.stokr.oms.journal.domain.ReplayCheckpoint;
import com.stokr.oms.journal.repository.ReplayCheckpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists replay cursor + journal tail hash for deterministic resume after crashes or partial runs.
 */
@Service
@RequiredArgsConstructor
public class ReplayCheckpointService {

    private final ReplayCheckpointRepository checkpointRepository;
    private final ReplayIntegrityService replayIntegrityService;

    @Transactional(readOnly = true)
    public Optional<ReplayCheckpoint> find(String streamType, String streamKey) {
        return checkpointRepository.findByStreamTypeAndStreamKeyAndDeletedFalse(streamType, streamKey);
    }

    /**
     * Upserts checkpoint to match the journal tail row (sequence + chain hash).
     */
    @Transactional
    public ReplayCheckpoint upsertTail(
            EventStoreEntry journalTail,
            UUID backtestRunId,
            UUID userId,
            String recoveryMetadataJson
    ) {
        ReplayCheckpoint cp = checkpointRepository
                .findByStreamTypeAndStreamKeyAndDeletedFalse(journalTail.getStreamType(), journalTail.getStreamKey())
                .orElseGet(ReplayCheckpoint::new);
        cp.setStreamType(journalTail.getStreamType());
        cp.setStreamKey(journalTail.getStreamKey());
        cp.setLastSequence(journalTail.getSequenceNum());
        cp.setCheckpointHash(journalTail.getChainHash());
        cp.setBacktestRunId(backtestRunId);
        cp.setUserId(userId);
        cp.setRecoveryMetadata(recoveryMetadataJson);
        return checkpointRepository.save(cp);
    }

    /**
     * Validates stored checkpoint vs journal tail and verifies hash chain through checkpoint sequence.
     */
    @Transactional(readOnly = true)
    public ReplayResumeValidation validateForResume(String streamType, String streamKey) {
        Optional<ReplayCheckpoint> cpOpt = find(streamType, streamKey);
        if (cpOpt.isEmpty()) {
            return new ReplayResumeValidation(false, false, false, Optional.empty(), "no checkpoint");
        }
        ReplayCheckpoint cp = cpOpt.get();
        boolean chainOk = replayIntegrityService.verifyStreamThrough(streamType, streamKey, cp.getLastSequence());
        boolean tailOk = replayIntegrityService.checkpointMatchesJournalTail(cp);
        boolean ok = chainOk && tailOk;
        String detail = ok ? "ok" : (!chainOk ? "hash_chain_failed" : "tail_mismatch");
        return new ReplayResumeValidation(true, chainOk, tailOk, cpOpt, detail);
    }

    public record ReplayResumeValidation(
            boolean checkpointPresent,
            boolean chainIntegrityOk,
            boolean tailMatchesJournal,
            Optional<ReplayCheckpoint> checkpoint,
            String detail
    ) {
        public boolean canResume() {
            return checkpointPresent && chainIntegrityOk && tailMatchesJournal && checkpoint.isPresent();
        }
    }
}
