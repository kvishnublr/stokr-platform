package com.stokr.oms.journal;

import com.stokr.oms.journal.domain.EventStoreEntry;
import com.stokr.oms.journal.domain.ReplayCheckpoint;
import com.stokr.oms.journal.repository.EventStoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReplayIntegrityService {

    private final EventStoreRepository eventStoreRepository;

    @Transactional(readOnly = true)
    public boolean verifyStream(String streamType, String streamKey) {
        List<EventStoreEntry> rows =
                eventStoreRepository.findByStreamTypeAndStreamKeyOrderBySequenceNumAsc(streamType, streamKey);
        return verifyOrderedEntries(rows);
    }

    /**
     * Validates hash chain for sequence numbers {@code 1 .. maxSequenceInclusive}.
     */
    @Transactional(readOnly = true)
    public boolean verifyStreamThrough(String streamType, String streamKey, long maxSequenceInclusive) {
        if (maxSequenceInclusive <= 0) {
            return true;
        }
        List<EventStoreEntry> rows =
                eventStoreRepository.findStreamPrefix(streamType, streamKey, maxSequenceInclusive);
        return verifyOrderedEntries(rows)
                && !rows.isEmpty()
                && rows.getLast().getSequenceNum() == maxSequenceInclusive;
    }

    /**
     * Confirms the persisted checkpoint matches the journal tail at {@link ReplayCheckpoint#getLastSequence()}.
     */
    @Transactional(readOnly = true)
    public boolean checkpointMatchesJournalTail(ReplayCheckpoint checkpoint) {
        return eventStoreRepository
                .findTopByStreamTypeAndStreamKeyOrderBySequenceNumDesc(checkpoint.getStreamType(), checkpoint.getStreamKey())
                .filter(tail -> tail.getSequenceNum() == checkpoint.getLastSequence())
                .filter(tail -> tail.getChainHash().equals(checkpoint.getCheckpointHash()))
                .isPresent();
    }

    private static boolean verifyOrderedEntries(List<EventStoreEntry> rows) {
        String prevChain = null;
        long expectSeq = 1;
        for (EventStoreEntry e : rows) {
            if (e.getSequenceNum() != expectSeq) {
                return false;
            }
            expectSeq++;
            String payloadHash = JournalHash.sha256Hex(e.getPayloadJson());
            if (!payloadHash.equals(e.getPayloadHash())) {
                return false;
            }
            String recomputedChain = JournalHash.chain(prevChain, payloadHash);
            if (!recomputedChain.equals(e.getChainHash())) {
                return false;
            }
            prevChain = e.getChainHash();
        }
        return true;
    }
}
