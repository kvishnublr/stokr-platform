package com.stokr.oms.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.journal.domain.EventStoreEntry;
import com.stokr.oms.journal.domain.EventStreamCounter;
import com.stokr.oms.journal.repository.EventStoreRepository;
import com.stokr.oms.journal.repository.EventStreamCounterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only deterministic journal with per-stream monotonic sequences and hash chains.
 */
@Service
@Slf4j
public class EventJournalService {

    private final EventStoreRepository eventStoreRepository;
    private final EventStreamCounterRepository counterRepository;
    private final ObjectMapper deterministicMapper;

    public EventJournalService(EventStoreRepository eventStoreRepository, EventStreamCounterRepository counterRepository) {
        this.eventStoreRepository = eventStoreRepository;
        this.counterRepository = counterRepository;
        this.deterministicMapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventStoreEntry append(
            String streamType,
            String streamKey,
            String eventType,
            Map<String, Object> payload,
            UUID userId,
            String symbol,
            String strategyKey,
            UUID backtestRunId
    ) {
        try {
            String json = deterministicMapper.writeValueAsString(payload);
            String payloadHash = JournalHash.sha256Hex(json);

            EventStreamCounter counter = acquireCounter(streamType, streamKey);

            long nextSeq = counter.getLastSequence() + 1;
            counter.setLastSequence(nextSeq);
            counterRepository.save(counter);

            EventStoreEntry prev =
                    eventStoreRepository.findTopByStreamTypeAndStreamKeyOrderBySequenceNumDesc(streamType, streamKey)
                            .orElse(null);
            String prevChain = prev != null ? prev.getChainHash() : null;
            String chainHash = JournalHash.chain(prevChain, payloadHash);

            EventStoreEntry e = new EventStoreEntry();
            e.setStreamType(streamType);
            e.setStreamKey(streamKey);
            e.setSequenceNum(nextSeq);
            e.setEventType(eventType);
            e.setPayloadJson(json);
            e.setPayloadHash(payloadHash);
            e.setPrevChainHash(prevChain);
            e.setChainHash(chainHash);
            e.setCorrelationId(CorrelationIdHolder.get());
            e.setUserId(userId);
            e.setSymbol(symbol);
            e.setStrategyKey(strategyKey);
            e.setBacktestRunId(backtestRunId);

            EventStoreEntry saved = eventStoreRepository.save(e);
            log.debug("journal.append stream={}/{} seq={} type={}", streamType, streamKey, nextSeq, eventType);
            return saved;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("journal serialization failed", ex);
        }
    }

    /** Convenience for ORDER stream keyed by order id. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventStoreEntry appendOrderEvent(UUID orderId, String eventType, Map<String, Object> payload, UUID userId) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("orderId", orderId.toString());
        return append(StreamKeys.ST_ORDER, StreamKeys.order(orderId), eventType, body, userId, null, null, null);
    }

    private EventStreamCounter acquireCounter(String streamType, String streamKey) {
        Optional<EventStreamCounter> locked = counterRepository.lockByStream(streamType, streamKey);
        if (locked.isPresent()) {
            return locked.get();
        }
        try {
            EventStreamCounter c = new EventStreamCounter();
            c.setStreamType(streamType);
            c.setStreamKey(streamKey);
            c.setLastSequence(0);
            counterRepository.save(c);
        } catch (DataIntegrityViolationException concurrentInsert) {
            log.debug("journal.counter.concurrent {}", concurrentInsert.getMessage());
        }
        return counterRepository.lockByStream(streamType, streamKey)
                .orElseThrow(() -> new IllegalStateException("counter missing after insert"));
    }
}
