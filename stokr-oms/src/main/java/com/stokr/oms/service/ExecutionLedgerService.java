package com.stokr.oms.service;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.journal.EventJournalService;
import com.stokr.oms.repository.OmsExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExecutionLedgerService {

    private final OmsExecutionRepository executionRepository;
    private final EventJournalService eventJournalService;

    @Transactional
    public OmsExecution appendExecution(
            OmsOrder order,
            String brokerExecutionId,
            BigDecimal quantity,
            BigDecimal avgPrice,
            String executionKind,
            Instant executionTimestamp,
            Long latencyMs,
            BigDecimal slippageBps,
            BigDecimal spreadBps,
            BigDecimal referencePrice,
            String replaySource,
            UUID replayRunId
    ) {
        long nextSequence = executionRepository.findTopByOrder_IdOrderByExecutionSequenceDesc(order.getId())
                .map(last -> last.getExecutionSequence() != null ? last.getExecutionSequence() + 1L : 1L)
                .orElse(1L);

        Instant ts = executionTimestamp != null ? executionTimestamp : Instant.now();
        String hash = executionHash(order.getId(), nextSequence, quantity, avgPrice, ts, replaySource, replayRunId);

        if (executionRepository.existsByExecutionHashAndDeletedFalse(hash)) {
            throw new IllegalStateException("Duplicate execution hash detected for order " + order.getId());
        }
        if (brokerExecutionId != null && !brokerExecutionId.isBlank()
                && executionRepository.existsByBrokerExecutionIdAndDeletedFalse(brokerExecutionId)) {
            throw new IllegalStateException("Duplicate broker execution id " + brokerExecutionId);
        }

        OmsExecution ex = new OmsExecution();
        ex.setOrder(order);
        ex.setBrokerExecutionId(brokerExecutionId);
        ex.setExecutionSequence(nextSequence);
        ex.setExecutionHash(hash);
        ex.setFilledQty(quantity);
        ex.setAvgPrice(avgPrice);
        ex.setExecutionKind(executionKind);
        ex.setFillTime(ts);
        ex.setExecutionTimestamp(ts);
        ex.setLatencyMs(latencyMs);
        ex.setSlippageBps(slippageBps);
        ex.setSpreadBps(spreadBps);
        ex.setReferencePrice(referencePrice);
        ex.setReplaySource(replaySource);
        ex.setReplayRunId(replayRunId);
        OmsExecution saved = executionRepository.save(ex);

        Map<String, Object> journal = new LinkedHashMap<>();
        journal.put("executionId", saved.getId().toString());
        journal.put("sequence", saved.getExecutionSequence());
        journal.put("filledQty", quantity);
        journal.put("avgPrice", avgPrice);
        journal.put("replaySource", replaySource);
        journal.put("replayRunId", replayRunId != null ? replayRunId.toString() : null);
        eventJournalService.appendOrderEvent(order.getId(), "EXECUTION_RECORDED", journal, order.getUserId());

        return saved;
    }

    public String executionHash(
            UUID orderId,
            long sequence,
            BigDecimal quantity,
            BigDecimal avgPrice,
            Instant executionTimestamp,
            String replaySource,
            UUID replayRunId
    ) {
        String payload = orderId + "|" + sequence + "|" + quantity + "|" + avgPrice + "|" + executionTimestamp + "|" + replaySource + "|" + replayRunId;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
