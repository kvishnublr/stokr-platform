package com.stokr.backtest.service;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReplayValidationService {

    private final StrategySignalRepository signalRepository;
    private final OmsExecutionRepository executionRepository;

    @Transactional(readOnly = true)
    public ReplayValidationReport validateRun(UUID runId) {
        long signalCount = signalRepository.countByBacktestRunId(runId);
        List<OmsExecution> executions = executionRepository.findAllForBacktestRunOrdered(runId);
        StringBuilder chain = new StringBuilder(256);
        chain.append("SIG:").append(signalCount).append("|");
        for (OmsExecution e : executions) {
            chain.append(e.getExecutionHash() != null ? e.getExecutionHash() : e.getId()).append("|");
            chain.append(e.getOrder().getId()).append("|");
            chain.append(e.getExecutionSequence() != null ? e.getExecutionSequence() : 0).append("|");
            chain.append(e.getFilledQty()).append("|");
            chain.append(e.getAvgPrice()).append("|");
        }
        String replayHash = sha256(chain.toString());
        return new ReplayValidationReport(true, 0, BigDecimal.ZERO, 0, replayHash, signalCount, executions.size());
    }

    @Transactional(readOnly = true)
    public ReplayValidationReport compareRuns(UUID runA, UUID runB) {
        ReplayValidationReport a = validateRun(runA);
        ReplayValidationReport b = validateRun(runB);
        boolean deterministic = a.replayHash().equals(b.replayHash());
        return new ReplayValidationReport(
                deterministic,
                deterministic ? 0 : 1,
                deterministic ? BigDecimal.ZERO : BigDecimal.ONE,
                deterministic ? 0 : 1,
                sha256(a.replayHash() + "|" + b.replayHash()),
                a.strategySignalCount(),
                a.executionEventCount()
        );
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record ReplayValidationReport(
            boolean deterministic,
            int signalMismatchCount,
            BigDecimal pnlMismatch,
            int executionMismatch,
            String replayHash,
            /** Persisted strategy_signal rows for this backtest run (observability). */
            long strategySignalCount,
            /** OMS execution rows linked to this run (observability). */
            long executionEventCount
    ) {
    }
}
